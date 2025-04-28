package org.comroid.eventserverstarter;

import lombok.SneakyThrows;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.GenericEvent;
import net.dv8tion.jda.api.events.guild.scheduledevent.update.ScheduledEventUpdateStatusEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.comroid.api.func.util.Command;
import org.comroid.api.func.util.Event;
import org.comroid.api.info.Log;
import org.comroid.api.io.FileHandle;
import org.comroid.api.java.StackTraceUtils;
import org.comroid.api.tree.Component;
import org.intellij.lang.annotations.Language;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class Program extends Component.Base {
    public static final Pattern EVENT_URL = Pattern.compile("event=(\\d+)");

    public static void main(String[] args) {
        var exec = new Program();
    }

    private final Map<Long, EventDetail>  eventServices = new ConcurrentHashMap<>();
    private       Event.Bus<GenericEvent> bus;
    private       JDA                     jda;
    private       Command.Manager         cmdr;

    @Override
    protected void $initialize() {
        var token = new FileHandle("/srv/discord/jumpy/terraria_event_bot.txt").getContent();

        this.bus  = new Event.Bus<>() {{
            register(Program.this);
        }};
        this.jda  = JDABuilder.createDefault(token, GatewayIntent.getIntents(GatewayIntent.ALL_INTENTS)).addEventListeners(new ListenerAdapter() {
            @Override
            public void onGenericEvent(@NotNull GenericEvent event) {
                bus.accept(event);
            }
        }).build();
        this.cmdr = new Command.Manager() {{
            new Adapter$JDA(jda);
            register(Program.this);
        }};
    }

    @Override
    @SneakyThrows
    protected void $lateInitialize() {
        jda.awaitReady();
    }

    @Override
    public Stream<Object> streamOwnChildren() {
        return Stream.of(bus, jda, cmdr);
    }

    @Command(permission = "8589934592") // perm: MANAGE_EVENTS
    public String setEvent(Message message, @Command.Arg String service) {
        long eventId;
        try {
            var refContent = Objects.requireNonNull(message.getReferencedMessage()).getContentRaw();
            var matcher    = EVENT_URL.matcher(refContent);
            if (!matcher.find()) throw new NullPointerException("Could not parse event ID from referenced message");
            eventId = Long.parseLong(matcher.group(1));
        } catch (NullPointerException npe) {
            return "Cannot find referenced event: " + StackTraceUtils.toString(npe);
        }

        var event = jda.getScheduledEventById(eventId);
        if (event == null) return "Cannot find event with ID " + eventId;

        eventServices.put(eventId, new EventDetail(service, message.getChannelIdLong()));
        return "Successfully linked event '%s' with service '%s'".formatted(event.getName(), service);
    }

    @Event.Subscriber
    public void onScheduledEventUpdateStatus(ScheduledEventUpdateStatusEvent event) {
        var detail = eventServices.getOrDefault(event.getScheduledEvent().getIdLong(), null);
        if (detail == null) return;

        String verb = "looked at (something went wrong)";
        switch (event.getNewStatus()) {
            case ACTIVE:
                startService(detail.service);
                verb = "started";
                break;
            case COMPLETED, CANCELED:
                stopService(detail.service);
                verb = "stopped";
                break;
        }

        var channel = event.getScheduledEvent().getChannel();
        if (channel == null) {
            Log.at(Level.WARNING, "Could not send response for " + detail);
            return;
        }
        channel.asTextChannel()
                .sendMessage("Event '%s' and affiliated service '%s' were *%s*".formatted(event.getScheduledEvent().getName(), detail.service, verb))
                .queue();
    }

    private void startService(String name) {
        bashExec("sudo systemctl start " + name);
    }

    private void stopService(String name) {
        bashExec("sudo systemctl stop " + name);
    }

    @SneakyThrows
    private void bashExec(@Language("bash") String command) {
        Runtime.getRuntime().exec(command.split(" "));
    }

    record EventDetail(String service, long channelId) {}
}
