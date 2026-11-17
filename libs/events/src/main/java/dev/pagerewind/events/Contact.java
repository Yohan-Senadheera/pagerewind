package dev.pagerewind.events;

/**
 * One way to reach a person.
 *
 * @param address Telegram chat id, or email address
 */
public record Contact(Channel channel, String address) {

    public enum Channel {
        TELEGRAM,
        EMAIL
    }

    public Contact {
        Require.nonNull(channel, "channel");
        Require.nonBlank(address, "address");
    }
}
