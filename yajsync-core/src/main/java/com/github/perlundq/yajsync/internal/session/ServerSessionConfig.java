/*
 * Rsync server -> client handshaking protocol
 *
 * Copyright (C) 1996-2011 by Andrew Tridgell, Wayne Davison, and others
 * Copyright (C) 2013-2016 Per Lundqvist
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.github.perlundq.yajsync.internal.session;

import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import com.github.perlundq.yajsync.FileSelection;
import com.github.perlundq.yajsync.RsyncProtocolException;
import com.github.perlundq.yajsync.RsyncSecurityException;
import com.github.perlundq.yajsync.internal.channels.ChannelEOFException;
import com.github.perlundq.yajsync.internal.channels.ChannelException;
import com.github.perlundq.yajsync.internal.text.Text;
import com.github.perlundq.yajsync.internal.text.TextConversionException;
import com.github.perlundq.yajsync.internal.util.ArgumentParser;
import com.github.perlundq.yajsync.internal.util.ArgumentParsingError;
import com.github.perlundq.yajsync.internal.util.BitOps;
import com.github.perlundq.yajsync.internal.util.Consts;
import com.github.perlundq.yajsync.internal.util.MemoryPolicy;
import com.github.perlundq.yajsync.internal.util.Option;
import com.github.perlundq.yajsync.internal.util.OverflowException;
import com.github.perlundq.yajsync.internal.util.Util;
import com.github.perlundq.yajsync.server.module.Module;
import com.github.perlundq.yajsync.server.module.ModuleException;
import com.github.perlundq.yajsync.server.module.ModuleSecurityException;
import com.github.perlundq.yajsync.server.module.Modules;
import com.github.perlundq.yajsync.server.module.RestrictedModule;
import com.github.perlundq.yajsync.server.module.RsyncAuthContext;

public class ServerSessionConfig extends SessionConfig
{
    private static final Logger _log =
        Logger.getLogger(ServerSessionConfig.class.getName());
    private final List<Path> _sourceFiles = new LinkedList<>();
    private Path _receiverDestination;
    private boolean _isDelete = false;
    private boolean _isIncrementalRecurse = false;
    private boolean _isSender = false;
    private boolean _isPreserveDevices = false;
    private boolean _isPreserveLinks = false;
    private boolean _isPreservePermissions = false;
    private boolean _isPreserveSpecials = false;
    private boolean _isPreserveTimes = false;
    private boolean _isPreserveUser = false;
    private boolean _isPreserveGroup = false;
    private boolean _isNumericIds = false;
    private boolean _isIgnoreTimes = false;
    private FileSelection _fileSelection = FileSelection.EXACT;
    private Module _module;
    private int _verbosity = 0;
    private boolean _isSafeFileList;


    /**
     * @throws IllegalArgumentException if charset is not supported
     */
    private ServerSessionConfig(ReadableByteChannel in, WritableByteChannel out,
                                Charset charset)
    {
        super(in, out, charset);
        int seedValue = (int) System.currentTimeMillis();
        _checksumSeed = BitOps.toLittleEndianBuf(seedValue);
    }

    /**
     * @throws RsyncSecurityException
     * @throws IllegalArgumentException if charset is not supported
     * @throws RsyncProtocolException if failing to encode/decode characters
     *         correctly
     * @throws RsyncProtocolException if failed to parse arguments sent by peer
     *         correctly
     */
    public static ServerSessionConfig handshake(Charset charset,
                                                ReadableByteChannel in,
                                                WritableByteChannel out,
                                                Modules modules)
        throws ChannelException, RsyncProtocolException, RsyncSecurityException
    {
        assert charset != null;
        assert in != null;
        assert out != null;
        assert modules != null;

        ServerSessionConfig instance = new ServerSessionConfig(in, out,
                                                               charset);
        try {
            instance.exchangeProtocolVersion();
            String moduleName = instance.receiveModule();

            if (moduleName.isEmpty()) {
                if (_log.isLoggable(Level.FINE)) {
                    _log.fine("sending module listing and exiting");
                }
                instance.sendModuleListing(modules.all());
                instance.sendStatus(SessionStatus.EXIT);
                instance._status = SessionStatus.EXIT;                          // FIXME: create separate status type instead
                return instance;
            }

            Module module = modules.get(moduleName);                            // throws ModuleException
            if (module instanceof RestrictedModule) {
                RestrictedModule restrictedModule = (RestrictedModule) module;
                module = instance.unlockModule(restrictedModule);               // throws ModuleSecurityException
            }
            instance.setModule(module);
            instance.sendStatus(SessionStatus.OK);
            instance._status = SessionStatus.OK;

            Collection<String> args = instance.receiveArguments();
            if (_log.isLoggable(Level.FINE)) {
                _log.fine("parsing arguments: " + args);
            }
            instance.parseArguments(args);
            instance.sendCompatibilities();
            instance.sendChecksumSeed();
            return instance;
        } catch (ArgumentParsingError | TextConversionException e) {
            throw new RsyncProtocolException(e);
        } catch (ModuleException e) {
            if (_log.isLoggable(Level.WARNING)) {
                _log.warning(e.getMessage());
            }
            instance.sendErrorStatus(e.getMessage());
            instance._status = SessionStatus.ERROR;
            return instance;
        } finally {
            instance.flush();
        }
    }

    /**
     * @throws RsyncProtocolException if failing to decode input characters
     *         using current character set
     * @throws RsyncProtocolException if peer sent premature null character
     * @throws RsyncProtocolException if peer sent too large amount of
     *         characters
     */
    private Module unlockModule(RestrictedModule restrictedModule)
        throws ModuleSecurityException, ChannelException, RsyncProtocolException
    {
        RsyncAuthContext authContext = new RsyncAuthContext(_characterEncoder);
        writeString(SessionStatus.AUTHREQ + authContext.challenge() + '\n');

        String userResponse = readLine();
        String[] userResponseTuple = userResponse.split(" ", 2);
        if (userResponseTuple.length != 2) {
            throw new RsyncProtocolException("invalid challenge " +
                "response " + userResponse);
        }

        String userName = userResponseTuple[0];
        String correctResponse = restrictedModule.authenticate(authContext,
                                                               userName);
        String response = userResponseTuple[1];
        if (response.equals(correctResponse)) {
            return restrictedModule.toModule();
        } else {
            throw new ModuleSecurityException("failed to authenticate " +
                                              userName);
        }
    }

    public int verbosity()
    {
        return _verbosity;
    }

    private void flush() throws ChannelException
    {
        _peerConnection.flush();
    }

    /**
     * @throws TextConversionException
     */
    private void sendModuleListing(Iterable<Module> modules)
        throws ChannelException
    {
        for (Module module : modules) {
            assert !module.name().isEmpty();
            if (module.comment().isEmpty()) {
                writeString(String.format("%-15s\n", module.name()));
            } else {
                writeString(String.format("%-15s\t%s\n",
                                          module.name(), module.comment()));
            }
        }
    }

    private void sendStatus(SessionStatus status) throws ChannelException
    {
        writeString(status.toString() + "\n");
    }

    private void sendErrorStatus(String msg) throws ChannelException
    {
        writeString(String.format("%s: %s\n",
                                  SessionStatus.ERROR.toString(), msg));
    }

    private void setModule(Module module)
    {
        _module = module;
    }

    private String readStringUntilNullOrEof() throws ChannelException,
                                                     RsyncProtocolException
    {
        ByteBuffer buf = ByteBuffer.allocate(64);
        try {
            while (true) {
                byte b = _peerConnection.getByte();
                if (b == Text.ASCII_NULL) {
                    break;
                } else if (!buf.hasRemaining()) {
                    buf = Util.enlargeByteBuffer(buf, MemoryPolicy.IGNORE,
                                                 Consts.MAX_BUF_SIZE);
                }
                buf.put(b);
            }
        } catch (OverflowException e) {
            throw new RsyncProtocolException(e);
        } catch (ChannelEOFException e) {
            // EOF is OK
        }
        buf.flip();
        try {
            return _characterDecoder.decode(buf);
        } catch (TextConversionException e) {
            throw new RsyncProtocolException(e);
        }
    }

    /**
     *
     * @
     * @throws ChannelException
     * @throws RsyncProtocolException
     */
    private Collection<String> receiveArguments() throws ChannelException,
                                                         RsyncProtocolException
    {
        Collection<String> list = new LinkedList<>();
        while (true) {
            String arg = readStringUntilNullOrEof();
            if (arg.isEmpty()) {
                break;
            }
            list.add(arg);
        }
        return list;
    }

    private void parseArguments(Collection<String> receivedArguments)
        throws ArgumentParsingError, RsyncProtocolException, RsyncSecurityException
    {
        ArgumentParser argsParser =
            ArgumentParser.newWithUnnamed("", "files...");
        // NOTE: has no argument handler
        argsParser.add(Option.newWithoutArgument(Option.Policy.REQUIRED,
                                                 "server", "", "", null));

        argsParser.add(Option.newWithoutArgument(
            Option.Policy.OPTIONAL,
            "sender", "", "",
            new Option.ContinuingHandler() {
                @Override public void handleAndContinue(Option option) {
                    setIsSender();
                }}));

        argsParser.add(Option.newWithoutArgument(
            Option.Policy.OPTIONAL,
            "recursive", "r", "",
            new Option.ContinuingHandler() {
                @Override public void handleAndContinue(Option option) {
                    _fileSelection = FileSelection.RECURSE;
                }}));

        argsParser.add(Option.newWithoutArgument(
            Option.Policy.OPTIONAL,
            "no-r", "", "",
            new Option.ContinuingHandler() {
                @Override public void handleAndContinue(Option option) {
                    // is sent when transfer dirs and delete
                    if (_fileSelection == FileSelection.RECURSE) {
                        _fileSelection = FileSelection.EXACT;
                    }
                }}));

        argsParser.add(Option.newStringOption(
            Option.Policy.REQUIRED,
            "rsh", "e", "",
            new Option.ContinuingHandler() {
                @Override public void handleAndContinue(Option option)
                        throws ArgumentParsingError
                {
                    try {
                        parsePeerCompatibilites((String) option.getValue());
                    } catch (RsyncProtocolException e) {
                        throw new ArgumentParsingError(e);
                    }
                }}));

        argsParser.add(Option.newWithoutArgument(
                Option.Policy.OPTIONAL,
                "ignore-times", "I", "",
                new Option.ContinuingHandler() {
                    @Override public void handleAndContinue(Option option) {
                        setIsIgnoreTimes();
                    }}));

        argsParser.add(Option.newWithoutArgument(
            Option.Policy.OPTIONAL,
            "verbose", "v", "",
            new Option.ContinuingHandler() {
                @Override public void handleAndContinue(Option option) {
                    increaseVerbosity();
                }}));

        argsParser.add(Option.newWithoutArgument(
            Option.Policy.OPTIONAL,
            "delete", "", "",
            new Option.ContinuingHandler() {
                @Override public void handleAndContinue(Option option) {
                    setIsDelete();
                }}));

        argsParser.add(Option.newWithoutArgument(
            Option.Policy.OPTIONAL,
            "", "D", "",
            new Option.ContinuingHandler() {
                @Override public void handleAndContinue(Option option) {
                    _isPreserveDevices = true;
                    _isPreserveSpecials = true;
                }}));

        argsParser.add(Option.newWithoutArgument(
            Option.Policy.OPTIONAL,
            "specials", "", "",
            new Option.ContinuingHandler() {
                @Override public void handleAndContinue(Option option) {
                    _isPreserveSpecials = true;
                }}));

        argsParser.add(Option.newWithoutArgument(
            Option.Policy.OPTIONAL,
            "no-specials", "", "",
            new Option.ContinuingHandler() {
                @Override public void handleAndContinue(Option option) {
                    _isPreserveSpecials = false;
                }}));

        argsParser.add(Option.newWithoutArgument(
            Option.Policy.OPTIONAL,
            "links", "l", "",
            new Option.ContinuingHandler() {
                @Override public void handleAndContinue(Option option) {
                    setIsPreserveLinks();
                }}));

        argsParser.add(Option.newWithoutArgument(
            Option.Policy.OPTIONAL,
            "owner", "o", "",
            new Option.ContinuingHandler() {
                @Override public void handleAndContinue(Option option) {
                    setIsPreserveUser();
                }}));

        argsParser.add(Option.newWithoutArgument(
            Option.Policy.OPTIONAL,
            "group", "g", "",
            new Option.ContinuingHandler() {
                @Override public void handleAndContinue(Option option) {
                    setIsPreserveGroup();
                }}));

        argsParser.add(Option.newWithoutArgument(
            Option.Policy.OPTIONAL,
            "numeric-ids", "", "",
            new Option.ContinuingHandler() {
                @Override public void handleAndContinue(Option option) {
                    setIsNumericIds();
                }}));

        argsParser.add(Option.newWithoutArgument(
            Option.Policy.OPTIONAL,
            "perms", "p", "",
            new Option.ContinuingHandler() {
                @Override public void handleAndContinue(Option option) {
                    setIsPreservePermissions();
                }}));

        argsParser.add(Option.newWithoutArgument(
            Option.Policy.OPTIONAL,
            "times", "t", "",
            new Option.ContinuingHandler() {
                @Override public void handleAndContinue(Option option) {
                    setIsPreserveTimes();
                }}));

        argsParser.add(Option.newWithoutArgument(
            Option.Policy.OPTIONAL,
            "dirs", "d", "",
            new Option.ContinuingHandler() {
                @Override public void handleAndContinue(Option option) {
                    _fileSelection = FileSelection.TRANSFER_DIRS;
                }}));

        // FIXME: let ModuleProvider mutate this argsParser instance before
        // calling parse (e.g. adding specific options or removing options)

        ArgumentParser.Status rc = argsParser.parse(receivedArguments);
        assert rc == ArgumentParser.Status.CONTINUE;
        assert _fileSelection != FileSelection.RECURSE || _isIncrementalRecurse :
               "We support only incremental recursive transfers for now";

        if (!isSender() && !_module.isWritable()) {
            throw new RsyncProtocolException(
                String.format("Error: module %s is not writable", _module));
        }

        List<String> unnamed = argsParser.getUnnamedArguments();
        if (unnamed.size() < 2) {
            throw new RsyncProtocolException(
                String.format("Got too few unnamed arguments from peer " +
                              "(%d), expected \".\" and more", unnamed.size()));
        }
        String dotSeparator = unnamed.remove(0);
        if (!dotSeparator.equals(Text.DOT)) {
            throw new RsyncProtocolException(
                String.format("Expected first non option-argument to be " +
                              "\".\", received \"%s\"", dotSeparator));
        }

        if (isSender()) {
            Pattern wildcardsPattern = Pattern.compile(".*[\\[*?].*");          // matches literal [, * or ?
            for (String fileName : unnamed) {
                if (wildcardsPattern.matcher(fileName).matches()) {
                    throw new RsyncProtocolException(
                        String.format("wildcards are not supported (%s)",
                                      fileName));
                }
                Path safePath = _module.restrictedPath().resolve(fileName);
                _sourceFiles.add(safePath);
            }
            if (_log.isLoggable(Level.FINE)) {
                _log.fine("sender source files: " + _sourceFiles);
            }
        } else {
            if (unnamed.size() != 1) {
                throw new RsyncProtocolException(String.format(
                    "Error: expected exactly one file argument: %s contains %d",
                    unnamed, unnamed.size()));
            }
            String fileName = unnamed.get(0);
            Path safePath = _module.restrictedPath().resolve(fileName);
            _receiverDestination = safePath.normalize();

            if (_log.isLoggable(Level.FINE)) {
                _log.fine("receiver destination: " + _receiverDestination);
            }
        }
    }

    private void increaseVerbosity()
    {
        _verbosity++;
    }

    // @throws RsyncProtocolException
    private void parsePeerCompatibilites(String str)
            throws RsyncProtocolException
    {
        if (str.startsWith(Text.DOT)) {
            if (str.contains("i")) { // CF_INC_RECURSE
                assert _fileSelection == FileSelection.RECURSE;
                _isIncrementalRecurse = true; // only set by client on --recursive or -r, but can also be disabled, we require it however (as a start)
            }
            if (str.contains("L")) { // CF_SYMLINK_TIMES
            }
            if (str.contains("s")) { // CF_SYMLINK_ICONV
            }
            _isSafeFileList = str.contains("f");
        } else {
            throw new RsyncProtocolException(
                String.format("Protocol not supported - got %s from peer",
                              str));
        }
    }

    private void sendCompatibilities() throws ChannelException
    {
        byte flags = 0;
        if (_isSafeFileList) {
            flags |= RsyncCompatibilities.CF_SAFE_FLIST;
        }
        if (_isIncrementalRecurse) {
            flags |= RsyncCompatibilities.CF_INC_RECURSE;
        }
        if (_log.isLoggable(Level.FINER)) {
            _log.finer("> (we support) " + flags);
        }
        _peerConnection.putByte(flags);
    }

    private void sendChecksumSeed() throws ChannelException
    {
        assert _checksumSeed != null;
        if (_log.isLoggable(Level.FINER)) {
            _log.finer("> (checksum seed) " +
                       BitOps.toBigEndianInt(_checksumSeed));
        }
        _peerConnection.putInt(BitOps.toBigEndianInt(_checksumSeed));
    }

    private void setIsDelete()
    {
        _isDelete = true;
    }

    private void setIsPreserveLinks()
    {
        _isPreserveLinks = true;
    }

    private void setIsPreservePermissions()
    {
        _isPreservePermissions = true;
    }

    private void setIsPreserveTimes()
    {
        _isPreserveTimes = true;
    }

    private void setIsPreserveUser()
    {
        _isPreserveUser = true;
    }

    private void setIsPreserveGroup()
    {
        _isPreserveGroup = true;
    }

    private void setIsNumericIds()
    {
        _isNumericIds = true;
    }

    private void setIsIgnoreTimes()
    {
        _isIgnoreTimes = true;
    }

    public boolean isSender()
    {
        return _isSender;
    }

    public List<Path> sourceFiles()
    {
        return _sourceFiles;
    }

    public boolean isDelete()
    {
        return _isDelete;
    }

    public boolean isPreserveDevices()
    {
        return _isPreserveDevices;
    }

    public boolean isPreserveLinks()
    {
        return _isPreserveLinks;
    }

    public boolean isPreservePermissions()
    {
        return _isPreservePermissions;
    }

    public boolean isPreserveSpecials()
    {
        return _isPreserveSpecials;
    }

    public boolean isPreserveTimes()
    {
        return _isPreserveTimes;
    }

    public boolean isPreserveUser()
    {
        return _isPreserveUser;
    }

    public boolean isPreserveGroup()
    {
        return _isPreserveGroup;
    }

    public boolean isNumericIds()
    {
        return _isNumericIds;
    }

    public boolean isIgnoreTimes()
    {
        return _isIgnoreTimes;
    }

    public boolean isSafeFileList()
    {
        return _isSafeFileList;
    }

    public Path getReceiverDestination()
    {
        assert _receiverDestination != null;
        return _receiverDestination;
    }

    private void setIsSender()
    {
        _isSender = true;
    }

    /**
     * @throws RsyncProtocolException if failing to decode input characters
     *         using current character set
     * @throws RsyncProtocolException if peer sent premature null character
     * @throws RsyncProtocolException if peer sent too large amount of
     *         characters
     */
    private String receiveModule() throws ChannelException,
                                          RsyncProtocolException
    {
        return readLine();
    }

    public FileSelection fileSelection()
    {
        return _fileSelection;
    }
}
