package org.eclipse.dse.spi.telemetry;

import com.fasterxml.jackson.annotation.JsonCreator;
import org.eclipse.edc.spi.types.domain.message.ProtocolRemoteMessage;

import java.util.Objects;

public class TelemetryRequestMessage extends ProtocolRemoteMessage {

    public static class Builder extends ProtocolRemoteMessage.Builder<TelemetryRequestMessage, Builder> {

        private Builder() {
            super(new TelemetryRequestMessage());
        }

        @JsonCreator
        public static Builder newInstance() {
            return new Builder();
        }

        @Override
        public Builder self() {
            return this;
        }

        @Override
        public TelemetryRequestMessage build() {
            Objects.requireNonNull(message.protocol, "protocol cannot be null");
            return message;
        }
    }
}
