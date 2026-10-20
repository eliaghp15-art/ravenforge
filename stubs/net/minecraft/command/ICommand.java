package net.minecraft.command;

public interface ICommand {
    String getCommandName();

    void processCommand(ICommandSender sender, String[] args);
}
