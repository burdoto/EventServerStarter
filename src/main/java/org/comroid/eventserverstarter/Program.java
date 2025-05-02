package org.comroid.eventserverstarter;

import lombok.SneakyThrows;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.ScheduledEvent;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.events.GenericEvent;
import net.dv8tion.jda.api.events.guild.scheduledevent.update.ScheduledEventUpdateStatusEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.comroid.annotations.Description;
import org.comroid.api.func.util.Command;
import org.comroid.api.func.util.Event;
import org.comroid.api.info.Log;
import org.comroid.api.io.FileHandle;
import org.comroid.api.tree.Component;
import org.intellij.lang.annotations.Language;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Pattern;

public class Program extends Component.Base {
    public static final Pattern SNOWFLAKE = Pattern.compile("(\\d+)");

    public static void main(String[] args) {
        try (var exec = new Program()) {
            exec.start();
        }
    }

    private final Map<Long, EventDetail>  eventServices = new ConcurrentHashMap<>();
    private       Event.Bus<GenericEvent> bus;
    private       JDA                     jda;
    private       Command.Manager         cmdr;

    @Override
    @SneakyThrows
    protected void $lateInitialize() {
        jda.awaitReady();
        cmdr.initialize();
        bus.start();
    }

    @Override
    protected void $terminate() {
        jda.shutdownNow();
        bus.close();
        cmdr.close();
    }

    @Override
    protected void $initialize() {
        var token = new FileHandle("/srv/discord/jumpy/terraria_event_bot.txt").getContent();

        this.bus  = new Event.Bus<>() {{
            register(Program.this);
        }};
        this.jda  = JDABuilder.createDefault(token, GatewayIntent.getIntents(GatewayIntent.ALL_INTENTS)).addEventListeners(new ListenerAdapter() {
            @Override
            public void onGenericEvent(@NotNull GenericEvent event) {
                var simpleName = event.getClass().getSimpleName();
                bus.accept(event, "on" + simpleName.substring(0, simpleName.length() - 5));
            }
        }).build();
        this.cmdr = new Command.Manager() {{
            new Adapter$JDA(jda);
            register(Program.this);
        }};
    }

    @Command(permission = "8589934592") // perm: MANAGE_EVENTS
    @Description("Link a discord event with a systemd service")
    public String link(
            @Command.Arg("event") @Description("The URL or any other string that contains the ID of a scheduled event") String eventHint,
            @Command.Arg("service") @Description("The systemd unit name to use") String service, Channel channel
    ) {
        long           eventId = 0;
        ScheduledEvent event   = null;
        var            matcher = SNOWFLAKE.matcher(eventHint);

        while (matcher.find()) {
            eventId = Long.parseLong(matcher.group(1));
            event   = jda.getScheduledEventById(eventId);
            if (event != null) break;
        }
        if (event == null) throw new Command.Error("Cannot find scheduled event with ID " + eventId);

        eventServices.put(eventId, new EventDetail(service, channel.getIdLong()));
        return "Successfully linked scheduled event '%s' with service '%s'".formatted(event.getName(), service);
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
        channel.asVoiceChannel()
                .sendMessage("Event '%s' and affiliated service '%s' were *%s*".formatted(event.getScheduledEvent().getName(), detail.service, verb))
                .queue();
    }

    private void startService(String name) {
        bashExec("sudo -n systemctl start " + name);
    }

    private void stopService(String name) {
        bashExec("sudo -n systemctl stop " + name);
    }

    @SneakyThrows
    private void bashExec(@Language("bash") String command) {
        Runtime.getRuntime().exec(command.split(" "));
    }

    record EventDetail(String service, long channelId) {}
}
