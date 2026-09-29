package org.eclipse.edc.test.system;

public class LocalConsumer extends AbstractParticipant {

    public static final String ASSET_ID_CONSUMER_REST_API = "consumer-rest-api";

    @Override
    protected String name() {
        return "consumer";
    }

}
