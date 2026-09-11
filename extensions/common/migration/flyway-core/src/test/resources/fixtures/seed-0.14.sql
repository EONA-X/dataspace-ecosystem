-- Rows in the 0.14 shape, one per table the migrations backfill. Exists so that the backfills run
-- against actual data: on an empty database every UPDATE touches zero rows and the following
-- ALTER COLUMN ... SET NOT NULL passes regardless of whether the backfill is correct.
--
-- Values are minimal: only what the 0.14 NOT NULL constraints require.

INSERT INTO edc_transfer_process (transferprocess_id, type, state, created_at, updated_at)
VALUES ('tp-1', 'CONSUMER', 800, 1, 1),
       ('tp-2', 'PROVIDER', 800, 2, 2);

INSERT INTO edc_contract_agreement (agr_id, asset_id)
VALUES ('agreement-1', 'asset-1'),
       ('agreement-2', 'asset-2');

INSERT INTO edc_contract_negotiation (id, created_at, updated_at, counterparty_id, counterparty_address,
                                      protocol, type, state, agreement_id)
VALUES ('cn-1', 1, 1, 'did:web:counterparty', 'https://counterparty', 'dataspace-protocol-http', 'CONSUMER', 800, 'agreement-1');

INSERT INTO edc_asset (asset_id, created_at)
VALUES ('asset-1', 1),
       ('asset-2', 2);

INSERT INTO edc_policydefinitions (policy_id, created_at, policy_type)
VALUES ('policy-1', 1, 'SET');

INSERT INTO edc_contract_definitions (contract_definition_id, created_at, access_policy_id, contract_policy_id, assets_selector)
VALUES ('cd-1', 1, 'policy-1', 'policy-1', '[]');

INSERT INTO edc_edr_entry (transfer_process_id, agreement_id, asset_id, provider_id, created_at)
VALUES ('tp-1', 'agreement-1', 'asset-1', 'did:web:provider', 1);

INSERT INTO edc_data_plane (process_id, state, created_at, updated_at)
VALUES ('df-1', 800, 1, 1);

INSERT INTO edc_telemetry_record (record_id, type, state, created_at, updated_at)
VALUES ('rec-1', 'TRANSFER', 100, 1, 1);

-- identity-hub and issuer-service.
--
-- The two credential_resource rows are not interchangeable: the migration reads the VC type to tell a
-- status list credential from a holder one, so both branches of that CASE need a row.

INSERT INTO credential_resource (id, create_timestamp, issuer_id, holder_id, vc_state, vc_format,
                                 verifiable_credential, participant_context_id)
VALUES ('cred-1', 1, 'did:web:issuer', 'did:web:holder', 200, 1,
        '{"type": ["VerifiableCredential", "MembershipCredential"]}', 'participant-1'),
       ('cred-2', 2, 'did:web:issuer', 'did:web:issuer', 200, 1,
        '{"type": ["VerifiableCredential", "BitstringStatusListCredential"]}', 'participant-1');

INSERT INTO keypair_resource (id, participant_context_id, timestamp, key_id, serialized_public_key, private_key_alias)
VALUES ('kp-1', 'participant-1', 1, 'key-1', 'public-key', 'private-key-alias');

-- id carries the participant context id in 0.14, which is what the migration backfills from.
INSERT INTO edc_sts_client (id, client_id, did, name, secret_alias, private_key_alias, public_key_reference, created_at)
VALUES ('participant-1', 'did:web:participant', 'did:web:participant', 'participant-1', 'secret-alias',
        'private-key-alias', 'did:web:participant#key-1', 1);

INSERT INTO edc_credential_offers (id, state, created_at, updated_at, participant_context_id, issuer_did)
VALUES ('offer-1', 100, 1, 1, 'participant-1', 'did:web:issuer');

INSERT INTO edc_holder_credentialrequest (id, state, created_at, updated_at, participant_context_id,
                                          issuer_did, ids_and_formats)
VALUES ('req-1', 100, 1, 1, 'participant-1', 'did:web:issuer', '{}');

INSERT INTO holders (holder_id, participant_context_id, did, created_date)
VALUES ('holder-1', 'participant-1', 'did:web:holder', 1);

INSERT INTO edc_issuance_process (id, state, created_at, updated_at, holder_id, participant_context_id,
                                  holder_pid, claims, credential_definitions, credential_formats)
VALUES ('ip-1', 100, 1, 1, 'holder-1', 'participant-1', 'holder-pid-1', '{}', '[]', '{}');
