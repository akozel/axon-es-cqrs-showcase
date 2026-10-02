package by.akozel.accountverification.account;

import org.axonframework.eventsourcing.configuration.EventSourcedEntityModule;
import org.axonframework.eventsourcing.configuration.EventSourcingConfigurer;

public final class AccountConfiguration {

    private AccountConfiguration() {}

    public static EventSourcingConfigurer configure(EventSourcingConfigurer configurer) {
        return configurer.registerEntity(EventSourcedEntityModule.autodetected(String.class, Account.class));
    }
}
