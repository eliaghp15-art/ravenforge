package ravenforge.client;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;

public class ClientCommandHandler {
    public static final ClientCommandHandler instance = new ClientCommandHandler();

    private final Map<String, ICommand> commands = new LinkedHashMap<String, ICommand>();

    public ICommand registerCommand(ICommand command) {
        if (command != null) {
            this.commands.put(command.getCommandName().toLowerCase(), command);
        }
        return command;
    }

    public int executeCommand(ICommandSender sender, String rawCommand) {
        if (rawCommand == null) {
            return 0;
        }
        String trimmed = rawCommand.startsWith("/") ? rawCommand.substring(1) : rawCommand;
        String[] parts = trimmed.split(" ");
        String label = parts.length == 0 ? trimmed : parts[0];
        ICommand command = this.commands.get(label.toLowerCase());
        if (command == null) {
            return 0;
        }
        String[] args = new String[Math.max(0, parts.length - 1)];
        if (parts.length > 1) {
            System.arraycopy(parts, 1, args, 0, parts.length - 1);
        }
        command.processCommand(sender, args);
        return 1;
    }

    public Map<String, ICommand> getCommands() {
        return this.commands;
    }
}
