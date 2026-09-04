package org.eclipse.edc.spi.core;

public enum MembershipTypes {
    FULL_MEMBER("FullMember"),
    EXTERNAL("External");

    private final String name;

    MembershipTypes(String name) {
        this.name = name;
    }

    public String getName() {
        return this.name;
    }

    @Override
    public String toString() {
        return this.name;
    }

}
