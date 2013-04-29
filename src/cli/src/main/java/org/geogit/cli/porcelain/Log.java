/* Copyright (c) 2013 OpenPlans. All rights reserved.
 * This code is licensed under the BSD New License, available at the root
 * application directory.
 */

package org.geogit.cli.porcelain;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import jline.Terminal;
import jline.console.ConsoleReader;

import org.fusesource.jansi.Ansi;
import org.fusesource.jansi.Ansi.Color;
import org.geogit.api.GeoGIT;
import org.geogit.api.ObjectId;
import org.geogit.api.Platform;
import org.geogit.api.Ref;
import org.geogit.api.RevCommit;
import org.geogit.api.RevPerson;
import org.geogit.api.plumbing.ForEachRef;
import org.geogit.api.plumbing.ParseTimestamp;
import org.geogit.api.plumbing.RefParse;
import org.geogit.api.plumbing.RevParse;
import org.geogit.api.plumbing.diff.DiffEntry;
import org.geogit.api.porcelain.DiffOp;
import org.geogit.api.porcelain.LogOp;
import org.geogit.cli.AbstractCommand;
import org.geogit.cli.AnsiDecorator;
import org.geogit.cli.CLICommand;
import org.geogit.cli.GeogitCLI;
import org.geotools.util.Range;

import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParametersDelegate;
import com.beust.jcommander.internal.Maps;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

/**
 * Shows the commit logs.
 * <p>
 * CLI proxy for {@link org.geogit.api.porcelain.LogOp}
 * <p>
 * Usage:
 * <ul>
 * <li> {@code geogit log [<options>]}
 * </ul>
 * 
 * @see org.geogit.api.porcelain.LogOp
 */
@Parameters(commandNames = "log", commandDescription = "Show commit logs")
public class Log extends AbstractCommand implements CLICommand {

    public enum LOG_DETAIL {
        SUMMARY, NAMES_ONLY, STATS, NOTHING
    };

    @ParametersDelegate
    public final LogArgs args = new LogArgs();

    private Map<ObjectId, String> refs;

    private GeoGIT geogit;

    private ConsoleReader console;

    private boolean useColor;

    /**
     * Executes the log command using the provided options.
     * 
     * @param cli
     * @throws IOException
     * @see org.geogit.cli.AbstractCommand#runInternal(org.geogit.cli.GeogitCLI)
     */
    @Override
    public void runInternal(GeogitCLI cli) throws Exception {
        final Platform platform = cli.getPlatform();
        Preconditions.checkState(cli.getGeogit() != null, "Not a geogit repository: "
                + platform.pwd().getAbsolutePath());

        Preconditions.checkArgument(!(args.summary && args.oneline),
                "--summary and --oneline cannot be used together");
        Preconditions.checkArgument(!(args.stats && args.oneline),
                "--stats and --oneline cannot be used together");
        Preconditions.checkArgument(!(args.stats && args.oneline),
                "--name-only and --oneline cannot be used together");

        geogit = cli.getGeogit();

        LogOp op = geogit.command(LogOp.class).setTopoOrder(args.topo)
                .setFirstParentOnly(args.firstParent).setAll(args.all);

        refs = Maps.newHashMap();
        if (args.decoration) {
            Optional<Ref> head = geogit.command(RefParse.class).setName(Ref.HEAD).call();
            refs.put(head.get().getObjectId(), Ref.HEAD);
            ImmutableSet<Ref> set = geogit.command(ForEachRef.class).call();
            for (Ref ref : set) {
                ObjectId id = ref.getObjectId();
                if (refs.containsKey(id)) {
                    refs.put(id, refs.get(id) + ", " + ref.getName());
                } else {
                    refs.put(id, ref.getName());
                }
            }
        }
        if (args.branch != null && !args.branch.isEmpty()) {
            op.setBranch(args.branch);
        }
        if (args.author != null && !args.author.isEmpty()) {
            op.setAuthor(args.author);
        }
        if (args.committer != null && !args.committer.isEmpty()) {
            op.setCommiter(args.committer);
        }
        if (args.skip != null) {
            op.setSkip(args.skip.intValue());
        }
        if (args.limit != null) {
            op.setLimit(args.limit.intValue());
        }
        if (args.since != null || args.until != null) {
            Date since = new Date(0);
            Date until = new Date();
            if (args.since != null) {
                since = new Date(geogit.command(ParseTimestamp.class).setString(args.since).call());
            }
            if (args.until != null) {
                until = new Date(geogit.command(ParseTimestamp.class).setString(args.until).call());
            }
            op.setTimeRange(new Range<Date>(Date.class, since, until));
        }
        if (!args.sinceUntilPaths.isEmpty()) {
            List<String> sinceUntil = ImmutableList.copyOf((Splitter.on("..")
                    .split(args.sinceUntilPaths.get(0))));
            Preconditions.checkArgument(sinceUntil.size() == 1 || sinceUntil.size() == 2,
                    "Invalid refSpec format, expected [<until>]|[<since>..<until>]: %s",
                    args.sinceUntilPaths.get(0));

            String sinceRefSpec;
            String untilRefSpec;
            if (sinceUntil.size() == 1) {
                // just until was given
                sinceRefSpec = null;
                untilRefSpec = sinceUntil.get(0);
            } else {
                sinceRefSpec = sinceUntil.get(0);
                untilRefSpec = sinceUntil.get(1);
            }
            if (sinceRefSpec != null) {
                Optional<ObjectId> since;
                since = geogit.command(RevParse.class).setRefSpec(sinceRefSpec).call();
                Preconditions.checkArgument(since.isPresent(), "Object not found '%s'",
                        sinceRefSpec);
                op.setSince(since.get());
            }
            if (untilRefSpec != null) {
                Optional<ObjectId> until;
                until = geogit.command(RevParse.class).setRefSpec(untilRefSpec).call();
                Preconditions.checkArgument(until.isPresent(), "Object not found '%s'",
                        sinceRefSpec);
                op.setUntil(until.get());
            }
        }
        if (!args.pathNames.isEmpty()) {
            for (String s : args.pathNames) {
                op.addPath(s);
            }
        }
        Iterator<RevCommit> log = op.call();
        console = cli.getConsole();
        Terminal terminal = console.getTerminal();
        switch (args.color) {
        case never:
            useColor = false;
            break;
        case always:
            useColor = true;
            break;
        default:
            useColor = terminal.isAnsiSupported();
        }

        if (!log.hasNext()) {
            console.println("No commits to show");
            console.flush();
            return;
        }

        LogEntryPrinter printer;
        if (args.raw) {
            printer = new RawPrinter();
        } else if (args.oneline) {
            printer = new OneLineConverter();
        } else {
            LOG_DETAIL detail;
            if (args.summary) {
                detail = LOG_DETAIL.SUMMARY;
            } else if (args.names) {
                detail = LOG_DETAIL.NAMES_ONLY;
            } else if (args.stats) {
                detail = LOG_DETAIL.STATS;
            } else {
                detail = LOG_DETAIL.NOTHING;
            }

            printer = new StandardConverter(detail, geogit.getPlatform());
        }

        while (log.hasNext()) {
            printer.print(log.next());
            console.flush();
        }
    }

    interface LogEntryPrinter {

        /**
         * @param geogit
         * @param console
         * @param entry
         * @throws IOException
         */
        void print(RevCommit commit) throws IOException;

    }

    private class OneLineConverter implements LogEntryPrinter {

        @Override
        public void print(RevCommit commit) throws IOException {
            Ansi ansi = AnsiDecorator.newAnsi(useColor);
            ansi.fg(Color.YELLOW).a(getIdAsString(commit.getId())).reset();
            String message = Strings.nullToEmpty(commit.getMessage());
            String title = Splitter.on('\n').split(message).iterator().next();
            ansi.a(" ").a(title);
            console.println(ansi.toString());
        }

    }

    /**
     * @param useColor
     * @param showSummary
     * @return
     */
    private class StandardConverter implements LogEntryPrinter {

        private SimpleDateFormat DATE_FORMAT;

        private long now;

        private LOG_DETAIL detail;

        public StandardConverter(final LOG_DETAIL detail, final Platform platform) {
            now = platform.currentTimeMillis();
            DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z");
            this.detail = detail;
        }

        @Override
        public void print(RevCommit commit) throws IOException {
            Ansi ansi = AnsiDecorator.newAnsi(useColor);

            ansi.a("Commit:  ").fg(Color.YELLOW).a(getIdAsString(commit.getId())).reset().newline();
            if (commit.getParentIds().size() > 1) {
                ansi.a("Merge: ");
                for (ObjectId parent : commit.getParentIds()) {
                    ansi.a(parent.toString().substring(0, 7)).a(" ");
                }
                ansi.newline();
            }
            ansi.a("Author:  ").fg(Color.GREEN).a(formatPerson(commit.getAuthor())).reset()
                    .newline();

            final long timestamp = commit.getAuthor().getTimestamp();
            final int timeZoneOffset = commit.getAuthor().getTimeZoneOffset();

            String friendlyString = estimateSince(now, timestamp);
            DATE_FORMAT.getCalendar().getTimeZone().setRawOffset(timeZoneOffset);
            String formattedDate = DATE_FORMAT.format(timestamp);

            ansi.a("Date:    (").fg(Color.RED).a(friendlyString).reset().a(") ").a(formattedDate)
                    .newline();
            ansi.a("Subject: ").a(commit.getMessage()).newline();
            if ((detail.equals(LOG_DETAIL.NAMES_ONLY)) && commit.getParentIds().size() == 1) {
                ansi.a("Affected paths:").newline();
                Iterator<DiffEntry> diff = geogit.command(DiffOp.class)
                        .setOldVersion(commit.parentN(0).get()).setNewVersion(commit.getId())
                        .call();
                DiffEntry diffEntry;
                while (diff.hasNext()) {
                    diffEntry = diff.next();
                    ansi.a("\t" + diffEntry.newPath()).newline();
                }
            }
            if (detail.equals(LOG_DETAIL.STATS) && commit.getParentIds().size() == 1) {

                Iterator<DiffEntry> diff = geogit.command(DiffOp.class)
                        .setOldVersion(commit.parentN(0).get()).setNewVersion(commit.getId())
                        .call();
                int adds = 0, deletes = 0, changes = 0;
                DiffEntry diffEntry;
                while (diff.hasNext()) {
                    diffEntry = diff.next();
                    switch (diffEntry.changeType()) {
                    case ADDED:
                        ++adds;
                        break;
                    case REMOVED:
                        ++deletes;
                        break;
                    case MODIFIED:
                        ++changes;
                        break;
                    }
                }

                ansi.a("Changes:");
                ansi.fg(Color.GREEN).a(adds).reset().a(" features added, ").fg(Color.YELLOW)
                        .a(changes).reset().a(" changed, ").fg(Color.RED).a(deletes).reset()
                        .a(" deleted.").reset().newline();
            }

            console.println(ansi.toString());
            if (detail.equals(LOG_DETAIL.SUMMARY) && commit.getParentIds().size() == 1) {
                ansi.a("Changes:").newline();
                Iterator<DiffEntry> diff = geogit.command(DiffOp.class)
                        .setOldVersion(commit.parentN(0).get()).setNewVersion(commit.getId())
                        .call();
                DiffEntry diffEntry;
                while (diff.hasNext()) {
                    diffEntry = diff.next();
                    if (detail.equals(LOG_DETAIL.SUMMARY)) {
                        new FullDiffPrinter(true, false).print(geogit, console, diffEntry);
                    }

                }
            }
        }
    }

    private class RawPrinter implements LogEntryPrinter {

        @Override
        public void print(RevCommit commit) throws IOException {
            Ansi ansi = AnsiDecorator.newAnsi(useColor);

            ansi.fg(Color.YELLOW).a("commit ").a(commit.getId().toString()).reset().newline();
            ansi.a("tree ").a(commit.getTreeId().toString()).newline();
            for (ObjectId parentId : commit.getParentIds()) {
                ansi.a("parent ").a(parentId.toString()).newline();
            }
            ansi.a("author ").a(format(commit.getAuthor())).newline();
            ansi.a("committer ").a(format(commit.getCommitter())).newline();

            ansi.newline();
            if (commit.getMessage() != null) {
                ansi.a("message ").a(commit.getMessage());
                ansi.newline();
            }
            Iterator<DiffEntry> diff = geogit.command(DiffOp.class)
                    .setOldVersion(commit.parentN(0).get()).setNewVersion(commit.getId()).call();
            DiffEntry diffEntry;
            while (diff.hasNext()) {
                diffEntry = diff.next();
                StringBuilder sb = new StringBuilder(diffEntry.changeType().toString()).append(" ")
                        .append(diffEntry.oldObjectId().toString()).append(" -> ")
                        .append(diffEntry.oldObjectId().toString());
                ansi.a(sb.toString());
            }
            console.println(ansi.toString());
        }

        private String format(RevPerson p) {
            StringBuilder sb = new StringBuilder();
            if (p.getName().isPresent()) {
                sb.append(p.getName().get()).append(' ');
            }
            if (p.getEmail().isPresent()) {
                sb.append('<').append(p.getEmail().get()).append("> ");
            }
            sb.append(p.getTimestamp()).append(' ').append(p.getTimeZoneOffset());
            return sb.toString();
        }

    }

    /**
     * Converts a RevPerson for into a readable string.
     * 
     * @param person the person to format.
     * @return the formatted string
     * @see RevPerson
     */
    private String formatPerson(RevPerson person) {
        StringBuilder sb = new StringBuilder();
        sb.append(person.getName().or("<name not set>"));

        if (person.getEmail().isPresent()) {
            sb.append(" <").append(person.getEmail().get()).append(">");
        }
        return sb.toString();
    }

    /**
     * Converts a timestamp into a readable string that represents the rough time since that
     * timestamp.
     * 
     * @param now
     * @param timestamp
     * @return
     */
    private String estimateSince(final long now, long timestamp) {
        long diff = now - timestamp;
        final long seconds = 1000;
        final long minutes = seconds * 60;
        final long hours = minutes * 60;
        final long days = hours * 24;
        final long weeks = days * 7;
        final long months = days * 30;
        final long years = days * 365;

        if (diff > years) {
            return diff / years + " years ago";
        }
        if (diff > months) {
            return diff / months + " months ago";
        }
        if (diff > weeks) {
            return diff / weeks + " weeks ago";
        }
        if (diff > days) {
            return diff / days + " days ago";
        }
        if (diff > hours) {
            return diff / hours + " hours ago";
        }
        if (diff > minutes) {
            return diff / minutes + " minutes ago";
        }
        if (diff > seconds) {
            return diff / seconds + " seconds ago";
        }
        return "just now";
    }

    /**
     * Returns an Id as a string, decorating or abbreviating it if needed
     * 
     * @param id
     * @return
     */
    private String getIdAsString(ObjectId id) {
        StringBuilder sb = new StringBuilder();
        if (args.abbrev) {
            sb.append(id.toString().substring(0, 7));
        } else {
            sb.append(id.toString());
        }
        if (refs.containsKey(id)) {
            sb.append(" (");
            sb.append(refs.get(id));
            sb.append(")");
        }

        return sb.toString();
    }
}
