package org.eclipse.edc.test.system;

public interface ParticipantConstants {

    // Configurable hostname for the cluster ingress
    String CLUSTER_HOSTNAME = System.getProperty("cluster.hostname", System.getenv().getOrDefault("CLUSTER_HOSTNAME", "localhost:80"));
    
    // Configurable kubectl context for accessing the Kubernetes cluster
    String KUBECTL_CONTEXT = System.getProperty("kubectl.context", System.getenv().getOrDefault("KUBECTL_CONTEXT", "kind-dse-cluster"));

    // Lourmel playground namespace prefix ("<developer>-dse-<participant>") — used by
    // DatabaseCleaner to reach the right namespace over SSH. Not derivable from CLUSTER_HOSTNAME
    // (the VM's IP doesn't encode the developer's name), so it's its own property.
    String DEVELOPER_NAMESPACE = System.getProperty("developer.namespace", System.getenv().getOrDefault("DEVELOPER_NAMESPACE", ""));

    // SSH user for reaching the Lourmel VM directly (DatabaseCleaner) — deliberately not routed
    // through KUBECTL_CONTEXT/local kubectl.
    String SSH_USER = System.getProperty("ssh.user", System.getenv().getOrDefault("SSH_USER", "ubuntu"));

    // Print the configuration for debugging
    static void printConfiguration() {
        System.out.println("🚀 =================================");
        System.out.println("🚀 SYSTEM TEST CONFIGURATION");
        System.out.println("🚀 =================================");
        System.out.println("🚀 Cluster Hostname: " + CLUSTER_HOSTNAME);
        System.out.println("🚀 Kubectl Context: " + KUBECTL_CONTEXT);
        System.out.println("🚀 Developer Namespace: " + DEVELOPER_NAMESPACE);
        System.out.println("🚀 SSH User: " + SSH_USER);
        System.out.println("🚀 =================================");
    }
}
