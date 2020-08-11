package devtools.common

import info.nightscout.comboctl.base.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.max
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import org.jline.reader.*
import org.jline.reader.impl.*
import org.jline.reader.impl.completer.*
import org.jline.terminal.*

/**
 * Class for errors in the command line itself.
 *
 * Such errors include problems with the command arguments, for
 * example, when an argument is set to an invalid value, when
 * arguments are missing, or if their combination is somehow
 * invalid etc. They do _not_ include problems with the execution
 * of the command itself. These exceptions will be caught by the
 * [CommandLineInterface] and cause it to print the exception's
 * message, but otherwise it will continue. So, for example, if
 * the command executes a function that fails somehow, it would
 * be incorrect to throw this exception. Use other exception
 * classes instead.
 *
 * @param message The message that would be shown in the console.
 */
class CommandLineException(message: String) : ComboException(message)

/**
 * Contains details about a command, to be used with [CommandsMap].
 *
 * The argument description notation goes as follows:
 *
 * Required arguments are enclosed in less-than and greater-than characters:
 *
 *     <argument name>
 *
 * Optional arguments additionally are enclosed in square brackets:
 *
 *     [<optional argument>]
 *
 * Variable arguments are denoted with one optinal argument and an
 * ellipsis ("..."):
 *
 *     [<optional argument> ...]
 *
 * Example of one required and one optional argument:
 *
 *     <argument1> [<argument2>]
 *
 * Example of one required argument and additional optional variable arguments:
 *
 *     <argument1> [<argument2> ...]
 *
 * @property minNumRequiredArguments The minimum number of arguments
 *           the command requires. If the user enters fewer than that,
 *           [CommandLineInterface] prints an usage message.
 * @property commandDescription Human-readable string describing what
 *           the command is good for.
 * @property argumentsDescription A human-readable string describing
 *           the arguments that this command expects. If the value of
 *           minNumRequiredArguments is 0, set this to an empty string.
 * @property handler Function that gets executed when this command is called.
 *           If this handler finds that something is wrong with the arguments,
 *           it should throw [CommandLineException].
 */
data class CommandEntry(
    val minNumRequiredArguments: Int,
    val commandDescription: String,
    val argumentsDescription: String,
    val handler: (arguments: List<String>) -> Unit
)

/**
 * Map containing the valid commands for the command line interface.
 *
 * Key = the name of the command. Value = [CommandEntry] describing
 * the command.
 */
typealias CommandsMap = Map<String, CommandEntry>

/**
 * Interactive command line interface (CLI), useful for development and testing tools.
 *
 * This takes care of driving the console and terminal, shortcuts for searching
 * through the command line history etc. The entered command lines are parsed
 * and tokenized. The handlers of registered commands get the list of parsed
 * arguments.
 *
 * This also handles the Unix SIGITN signal, and end-of-file situations in the
 * console (usually caused by the user entering Ctrl+D), making a graceful exit
 * possible, should these events occur.
 *
 * The command line interface is started by the [CommandLineInterface.run]
 * function. This function blocks, but is a suspend function, so it suspends
 * the current Kotlin coroutine and does not block the entire thread.
 *
 * A quit command can be implemented by adding a "quit" command to the supplied
 * [CommandsMap], with the handler simply cancelling the coroutine scope where
 * [CommandLineInterface.run] is being executed.
 *
 * @property commands Map containing the commands to be supported in this CLI.
 * @property onStop Callback to be executed whenever the CLI finishes, be it
 *           because the associated coroutine scope is canceled, or because
 *           a Unix signal was caught, or because an exception was thrown.
 *           Default value does nothing.
 */
class CommandLineInterface(private val commands: CommandsMap, private val onStop: () -> Unit = {}) {
    private val terminal: Terminal
    private val completer: Completer
    private val cmdlineParser: Parser
    private val cmdlineReader: LineReader

    private var longestCommandName: Int = -1

    // The LineReader.readLine() function blocks the thread until the user
    // finishes entering a command line. jline distinguishes internally between
    // two modes: readLine() is active, accepting input, and readLine() is
    // inactive, not listening to input. This distinction is important, because
    // only in the active state will LineReader.callWidget() commands be valid.
    // Calling these in the inactive state will cause exceptions to be thrown.
    //
    // In order to ensure that printed lines do not interfere with the command
    // line prompt (as described in the printLine() documentation), callWidget()
    // must be used while the line reader is active. However, readLine() is
    // run in a separate thread (in a Dispatchers.IO context), so it is possible
    // that while it is internally starting to set up the prompt, printLine()
    // is run, leading to a race condition, because any check that printLine()
    // may do to see in what mode readLine() is currently in can change at any
    // moment during execution.
    //
    // The solution is to install callbacks that are run inside readLine() at
    // specific stages. A mutex (a ReentrantLock, not a kotlin concurrenty Mutex)
    // is used to make sure printLine() cannot run while readLine ist starting
    // or finishing. (Since readLine() blocks, it cannot simply be surrounded
    // by the mutex, or else printLine() would be blocked until readLine()
    // finishes.)
    //
    // The sequence therefore goes as follows:
    //
    // 1. Mutex is locked.
    // 2. readLine() is called.
    // 3. readLine() switches to active mode.
    // 4. Inside readLine(), the CALLBACK_INIT widget is called. Our callback
    //    is invoked, and this callback unlocks the mutex.
    // 5. readLine() listens to input and blocks until a line is entered,
    //    or a Unix signal like SIGINT is caught, of an end-of-file event happens.
    // 6. Inside readLien(), the CALLBACK_FINISH widget is called. Our callback
    //    is invoked, and this callback locks the mutex.
    // 7. readLine() switches to inactive mode.
    // 8. readLine() finishes.
    // 9. Mutex is unlocked.
    //
    // This ensures that whenever printLine() is called, it can never happen
    // that the readline mode switches in the middle of printLine()'s execution.
    private val lineMutex = ReentrantLock()

    init {
        terminal = TerminalBuilder.builder().build()
        completer = StringsCompleter(commands.keys)
        cmdlineParser = DefaultParser()
        cmdlineReader = LineReaderBuilder.builder()
            .terminal(terminal)
            .completer(completer)
            .parser(cmdlineParser)
            .build()

        // Custom callbacks necessary for avoiding a race condition.
        // See the lineMutex description above for details.
        cmdlineReader.getWidgets().put(LineReader.CALLBACK_INIT, Widget {
            lineMutex.unlock()
            true
        })
        cmdlineReader.getWidgets().put(LineReader.CALLBACK_FINISH, Widget {
            lineMutex.lock()
            true
        })

        for ((name, entry) in commands)
            longestCommandName = max(longestCommandName, name.length)
    }

    /**
     * Prints a line on the terminal. If currently there is an input
     * prompt shown, this clears the current line, prints the specified
     * line on the terminal, then restores the prompt.
     *
     * If this is not done, the user may end up with mixed content.
     * Example where the prompt is "cmd >" and a counter is counting
     * up periodically in another coroutine, printing "[Counter value X]",
     * and the user started to enter the "enable" command:
     *
     *   cmd> ena[Counter value 12]
     *   ble
     *
     * @param line Line to print.
     */
    fun printLine(line: String) {
        try {
            // See the lineMutex description above for why this is needed.
            lineMutex.lock()

            // If isReading() returns true, the line reader is in the
            // "active" mode. See the lineMutex description above
            // for details.
            if (cmdlineReader.isReading()) {
                cmdlineReader.callWidget(LineReader.CLEAR)
                cmdlineReader.getTerminal().writer().println(line)
                cmdlineReader.callWidget(LineReader.REDRAW_LINE)
                cmdlineReader.callWidget(LineReader.REDISPLAY)
                cmdlineReader.getTerminal().writer().flush()
            } else {
                cmdlineReader.getTerminal().writer().println(line)
                cmdlineReader.getTerminal().writer().flush()
            }
        } finally {
            try {
                lineMutex.unlock()
            } catch (e: IllegalMonitorStateException) {
            }
        }
    }

    suspend fun run(prompt: String) {
        while (true) {
            val line = readLine(prompt)

            // A null String? value means that the line input was 
            // interrupted by a SIGINT signal, or because EOF
            // happened (typically because the user entered Ctrl+D).
            if (line == null) {
                stop()
                break
            }

            // Parse and tokenize the entered line. Ignore
            // empty lines. words[0] is the command name,
            // the rest are the arguments.
            val parsedLine = cmdlineParser.parse(line, 0)
            val words = parsedLine.words()
            if (words.isEmpty())
                continue

            // Also ignore empty command names.
            val command = words[0]
            if (command.isEmpty())
                continue

            // "help" is a special command handled internally.
            if (command == "help") {
                printHelp()
                continue
            }

            // Retrieve the corresponding command entry.
            val commandEntry = commands.get(command)
            if (commandEntry == null) {
                printLine("Invalid command \"$command\"")
                continue
            }

            // Extract the arguments out of the words collection
            // for sake of clarity.
            val arguments = words.subList(1, words.size)

            if (arguments.size < commandEntry.minNumRequiredArguments) {
                printLine("Usage: $command ${commandEntry.argumentsDescription}")
                continue
            }

            // Execute the command handle, handing it the entered arguments.
            // Caught CommandLineException are just printed on screen. Other
            // exceptions lead to the CLI stopping, and the exceptions then
            // being rethrown, since these are considered non-recoverable
            // error situations (since we do not know what to do with them).
            try {
                commandEntry.handler(arguments)
            } catch (e: CommandLineException) {
                printLine(e.toString())
            } catch (e: Exception) {
                stop()
                throw e
            } catch (e: Error) {
                stop()
                throw e
            }
        }
    }

    private suspend fun readLine(prompt: String): String? = withContext(Dispatchers.IO) {
        try {
            // See the lineMutex description above for why this is needed.
            lineMutex.lock()
            cmdlineReader.readLine(prompt)
        } catch (e: org.jline.reader.UserInterruptException) {
            // User entered Ctrl+C, or SIGINT was sent to this process
            // in some other way.
            null
        } catch (e: org.jline.reader.EndOfFileException) {
            // User entered Ctrl+D. 
            null
        } finally {
            try {
                lineMutex.unlock()
            } catch (e: IllegalMonitorStateException) {
            }
        }
    }

    private fun printHelp() {
        if (!commands.isEmpty()) {
            printLine("List of available commands:")
            printLine("")
            for ((name, entry) in commands) {
                printLine("  ${name.padEnd(longestCommandName)} - ${entry.commandDescription}")
            }
            printLine("")
        } else
            printLine("No commands registered - this is most likely a bug!")
    }

    private fun stop() {
        onStop()
    }
}
