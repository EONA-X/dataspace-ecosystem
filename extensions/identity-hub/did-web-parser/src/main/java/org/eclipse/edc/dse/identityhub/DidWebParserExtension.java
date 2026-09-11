package org.eclipse.edc.dse.identityhub;

import org.eclipse.edc.identityhub.spi.did.DidWebParser;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

import java.net.URI;
import java.nio.charset.Charset;

@Extension(value = DidWebParserExtension.NAME)
public class DidWebParserExtension implements ServiceExtension {

    public static final String NAME = "Did Web Parser";

    @Override
    public String name() {
        return NAME;
    }

    @Provider
    public DidWebParser didWebParser(ServiceExtensionContext context) {
        return new DidWebParser() {
            @Override
            public String parse(URI url, Charset charset) {
                return context.getSetting("edc.participant.id", ServiceExtensionContext.ANONYMOUS_PARTICIPANT);
            }
        };
    }
}