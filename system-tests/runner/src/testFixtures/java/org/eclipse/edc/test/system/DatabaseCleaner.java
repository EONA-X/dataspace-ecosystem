package org.eclipse.edc.test.system;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.eclipse.edc.test.system.ParticipantConstants.CLUSTER_HOSTNAME;
import static org.eclipse.edc.test.system.ParticipantConstants.DEVELOPER_NAMESPACE;
import static org.eclipse.edc.test.system.ParticipantConstants.SSH_USER;

/**
 * Resets a Lourmel playground participant's Postgres state between suite runs, so the suite can
 * be replayed without a full destroy+redeploy.
 */
public class DatabaseCleaner {

    private static final Pattern TABLE_NAME_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
    private static final int PSQL_MAX_ATTEMPTS = 3;
    private static final int PSQL_TIMEOUT_SECONDS = 60;

    /**
     * Removes only the dead ends in edc_holder_credentialrequest: state=500 (ERROR, mirrors
     * purge_error_credential_requests() in init-dataspace-playground.sh) and state=100 (CREATED,
     * orphaned — safe because requestCredential() always mints a fresh random holderPid, so a
     * stale CREATED row is never revisited). Rows in state=400 (ISSUED) are never touched: they
     * may be in active use and are expensive to re-issue (see AbstractEntity.requestCredential's
     * javadoc for the issuance chain this protects).
     */
    private static final String PURGE_DEAD_CREDENTIAL_REQUESTS_SQL =
            "DELETE FROM edc_holder_credentialrequest WHERE state IN (100, 500);";
    /**
     * Mirrors purge_stale_leases() in init-dataspace-playground.sh: only leases whose duration has
     * actually elapsed, never a blanket TRUNCATE.
     */
    private static final String PURGE_EXPIRED_LEASES_SQL =
            "DELETE FROM edc_lease WHERE (leased_at + lease_duration) < (extract(epoch FROM now()) * 1000);";
    /**
     * Blanket TRUNCATE of edc_jti_validation — safe because this table is a pure anti-replay cache
     * (self-issued DCP token ids), never something the suite depends on across runs. Not a
     * targeted DELETE like the leases purge above: upstream EDC 0.14.0's
     * JtiValidationRule.checkRule() (jwt-verifiable-credentials) stores every entry via
     * {@code new JtiValidationEntry(jti)} — the no-expiration constructor — so expires_at is NULL
     * for every row ever written. EDC's own built-in reaper (IdentityAndTrustExtension,
     * jtiValidationStore::deleteExpired every 60s by default) deletes WHERE expires_at < now,
     * which never matches a NULL column in SQL, so it silently reaps nothing.
     */
    private static final String PURGE_JTI_VALIDATION_SQL = "TRUNCATE TABLE edc_jti_validation CASCADE;";

    private static final String CONTROL_PATH = "/tmp/dse-e2e-ssh-" + ProcessHandle.current().pid() + ".sock";


    /**
     * true if every given table is empty. Used in @BeforeAll to check the environment is in the
     * state a previous run's @AfterAll should have left it in, before deciding whether a forced
     * fallback reset is needed.
     */
    public static boolean isEmpty(String participant, List<String> tables) {
        if (tables.isEmpty()) {
            return true;
        }
        validate(tables);
        var unionCount = tables.stream()
                .map(t -> "SELECT count(*) AS n FROM " + t)
                .collect(Collectors.joining(" UNION ALL "));
        var sql = "SELECT COALESCE(SUM(n), 0) FROM (" + unionCount + ") x;";
        return "0".equals(psql(participant, sql).trim());
    }

    /**
     * TRUNCATE CASCADE a list of tables in a participant's DB. Never pass edc_holder_credentialrequest,
     * participant_context, or edc_lease here — see purgeStaleEntries() for the targeted,
     * non-destructive handling those two need instead.
     */
    public static void truncateTables(String participant, List<String> tables) {
        if (tables.isEmpty()) {
            return;
        }
        validate(tables);
        psql(participant, "TRUNCATE TABLE " + String.join(", ", tables) + " CASCADE;");
    }

    /**
     * Purges edc_holder_credentialrequest dead ends, expired edc_lease rows, and stale
     * edc_jti_validation entries in a single SSH/kubectl-exec/psql round trip — beforeAll/afterAll always run these three
     * back-to-back for the same participant/pod, no reason to pay three separate connection
     * handshakes for it. No behavior change from three separate calls: without
     * -1/--single-transaction, psql still autocommits each `;`-separated statement independently.
     */
    public static void purgeStaleEntries(String participant) {
        psql(participant, PURGE_DEAD_CREDENTIAL_REQUESTS_SQL + PURGE_EXPIRED_LEASES_SQL + PURGE_JTI_VALIDATION_SQL);
    }

    private static void validate(List<String> tables) {
        for (String table : tables) {
            if (!TABLE_NAME_PATTERN.matcher(table).matches()) {
                throw new IllegalArgumentException("Invalid table name (SQL injection guard): " + table);
            }
        }
    }

    /**
     * CLUSTER_HOSTNAME follows nip.io's own convention (IP address dash-encoded into the
     * hostname).
     */
    private static String sshHost() {
        var host = CLUSTER_HOSTNAME.split(":")[0];
        var suffixIndex = host.indexOf(".nip.io");
        if (suffixIndex < 0) {
            throw new IllegalStateException(
                    "cluster.hostname '%s' isn't in the expected nip.io format (e.g. 192-168-1-220.nip.io) — DatabaseCleaner can't derive the VM's SSH IP from it."
                            .formatted(CLUSTER_HOSTNAME));
        }
        return host.substring(0, suffixIndex).replace('-', '.');
    }

    private static final class PsqlDeadlockException extends RuntimeException {
        PsqlDeadlockException(String message) {
            super(message);
        }
    }

    /**
     * Retries only on a Postgres deadlock (see PsqlDeadlockException) — every other failure
     * (bad config, a genuine SQL error, SSH/IO trouble) is deterministic and would just fail the
     * same way three times, so it's left to propagate on the first attempt instead. Postgres
     * always resolves a deadlock itself by killing one side — ours, here — so a bare retry is the
     * standard mitigation: the killed transaction's locks are already released by the time this
     * catches the failure, so the same collision essentially never repeats immediately.
     */
    private static String psql(String participant, String sql) {
        PsqlDeadlockException lastFailure = null;
        for (int attempt = 1; attempt <= PSQL_MAX_ATTEMPTS; attempt++) {
            try {
                return psqlOnce(participant, sql);
            } catch (PsqlDeadlockException e) {
                if (lastFailure != null) {
                    e.addSuppressed(lastFailure);
                }
                lastFailure = e;
            }
        }
        throw lastFailure;
    }

    private static String psqlOnce(String participant, String sql) {
        if (DEVELOPER_NAMESPACE.isBlank()) {
            throw new IllegalStateException(
                    "developer.namespace not set (-Ddeveloper.namespace=<your-vm-firstname>) — DatabaseCleaner can't determine which K8s namespace to clean.");
        }
        var namespace = DEVELOPER_NAMESPACE + "-dse-" + participant;
        var pod = participant + "-postgresql-0";
        var db = participant + "db";
        var dbUser = participant + "connector";

        var result = runSsh(remoteCommand(namespace, participant, pod, dbUser, db), sql, pod);
        if (result.exitCode() != 0) {
            var message = "psql failed on %s (%s): %s".formatted(pod, namespace, result.output());
            if (result.output().contains("deadlock detected")) {
                throw new PsqlDeadlockException(message);
            }
            throw new RuntimeException(message);
        }
        return result.output();
    }


    private static String remoteCommand(String namespace, String participant, String pod, String dbUser, String db) {
        return ("PW=$(kubectl -n %s get secret %s-db-credentials -o jsonpath='{.data.password}' | base64 -d); " +
                "kubectl -n %s exec -i %s -- env PGPASSWORD=\"$PW\" psql -U %s -d %s -t -A")
                .formatted(namespace, participant, namespace, pod, dbUser, db);
    }

    private record ProcessResult(int exitCode, String output) {
    }

    /**
     * Runs remoteCommand over ssh, feeding stdin on its stdin and capturing combined
     * stdout+stderr to a temp file.
     */
    private static ProcessResult runSsh(String remoteCommand, String stdin, String pod) {
        Path outputFile;
        try {
            outputFile = Files.createTempFile("psql-output-", ".txt");
        } catch (IOException e) {
            throw new RuntimeException("Couldn't create the temp file for psql output", e);
        }
        try {
            var process = new ProcessBuilder("ssh", "-o", "BatchMode=yes",
                    "-o", "ControlMaster=auto", "-o", "ControlPersist=60s", "-o", "ControlPath=" + CONTROL_PATH,
                    SSH_USER + "@" + sshHost(), remoteCommand)
                    .redirectErrorStream(true)
                    .redirectOutput(outputFile.toFile())
                    .start();
            try (var stdinStream = process.getOutputStream()) {
                stdinStream.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
            waitWithTimeout(process, pod);
            return new ProcessResult(process.exitValue(), Files.readString(outputFile).trim());
        } catch (IOException e) {
            throw new RuntimeException("I/O error while accessing the database on " + pod, e);
        } finally {
            deleteQuietly(outputFile);
        }
    }

    /**
     * Blocks until the process exits or PSQL_TIMEOUT_SECONDS elapses, killing it on timeout.
     */
    private static void waitWithTimeout(Process process, String pod) {
        boolean finished;
        try {
            finished = process.waitFor(PSQL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while accessing the database on " + pod, e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException(
                    "Timeout (%ds) while accessing the database on %s — ssh/kubectl exec is not responding."
                            .formatted(PSQL_TIMEOUT_SECONDS, pod));
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best-effort temp-file cleanup — nothing to do if it fails
        }
    }
}
