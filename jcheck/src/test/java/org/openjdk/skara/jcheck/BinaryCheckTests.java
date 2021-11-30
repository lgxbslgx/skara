/*
 * Copyright (c) 2019, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.skara.jcheck;

import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.CommitMessage;
import org.openjdk.skara.vcs.openjdk.CommitMessageParsers;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.time.ZonedDateTime;
import java.io.IOException;

class BinaryCheckTests {
    private static final JCheckConfiguration conf = JCheckConfiguration.parse(List.of(
        "[general]",
        "project = test",
        "[checks]",
        "error = binary",
        "[checks \"binary\"]",
        ".*\\.bin=1b",
        ".*\\.o=1k"
    ));

    private static List<Diff> textualParentDiffs(String filename, String mode) {
        var hunk = new Hunk(new Range(1, 0), List.of(),
                            new Range(1, 1), List.of("An additional line"));
        var patch = new TextualPatch(Path.of(filename), FileType.fromOctal("100644"), Hash.zero(),
                                     Path.of(filename), FileType.fromOctal(mode), Hash.zero(),
                                     Status.from('M'), List.of(hunk));
        var diff = new Diff(Hash.zero(), Hash.zero(), List.of(patch));
        return List.of(diff);
    }

    private static List<Diff> binaryParentDiffs(Path path, Status status, int inflatedSize, List<String> data) {
        var hunk = BinaryHunk.ofLiteral(inflatedSize, data);
        var patch = new BinaryPatch(null, null, null, path,
                                    FileType.fromOctal("100644"), Hash.zero(), status, List.of(hunk));
        var diff = new Diff(Hash.zero(), Hash.zero(), List.of(patch));
        return List.of(diff);
    }

    private static Commit commit(List<Diff> parentDiffs) {
        var author = new Author("foo", "foo@host.org");
        var hash = new Hash("0123456789012345678901234567890123456789");
        var parents = List.of(hash, hash);
        var message = List.of("A commit");
        var authored = ZonedDateTime.now();
        var metadata = new CommitMetadata(hash, parents, author, authored, author, authored, message);
        return new Commit(metadata, parentDiffs);
    }

    private List<Issue> toList(Iterator<Issue> i) {
        var list = new ArrayList<Issue>();
        while (i.hasNext()) {
            list.add(i.next());
        }
        return list;
    }

    private static CommitMessage message(Commit c) {
        return CommitMessageParsers.v1.parse(c);
    }

    @Test
    void regularFileShouldPass() throws IOException {
        var commit = commit(textualParentDiffs("README", "100644"));
        var message = message(commit);
        var check = new BinaryCheck();
        var issues = toList(check.check(commit, message, conf, null));
        assertEquals(0, issues.size());
    }

    @Test
    void binaryFileNotLimited() throws IOException {
        // The size of the file `*.s` is not limited in the config file.
        Path path = Path.of("file.s");
        Files.deleteIfExists(path);
        Files.createFile(path);
        Files.write(path, List.of("testtest"));
        for (var status : List.of(Status.from("A"), Status.from("M"), Status.from("U"), Status.from("R100"), Status.from("C100"))) {
            var commit = commit(binaryParentDiffs(path, status, 9, List.of("testtest")));
            var message = message(commit);
            var check = new BinaryCheck();
            var issues = toList(check.check(commit, message, conf, null));
            assertEquals(0, issues.size());
        }
        Files.deleteIfExists(path);
    }

    @Test
    void binaryFileInRange() throws IOException {
        // The size of the file `*.o` is limited to 1k in the config file.
        Path path = Path.of("file.o");
        Files.deleteIfExists(path);
        Files.createFile(path);
        Files.write(path, List.of("testtest"));
        for (var status : List.of(Status.from("A"), Status.from("M"), Status.from("U"), Status.from("R100"), Status.from("C100"))) {
            var commit = commit(binaryParentDiffs(path, status, 9, List.of("testtest")));
            var message = message(commit);
            var check = new BinaryCheck();
            var issues = toList(check.check(commit, message, conf, null));
            assertEquals(0, issues.size());
        }
        Files.deleteIfExists(path);
    }

    @Test
    void binaryFileTooLarge() throws IOException {
        // The size of the file `*.bin` is limited to 1b in the config file.
        Path path = Path.of("file.bin");
        Files.deleteIfExists(path);
        Files.createFile(path);
        Files.write(path, List.of("testtest"));
        for (var status : List.of(Status.from("A"), Status.from("M"), Status.from("U"), Status.from("R100"), Status.from("C100"))) {
            var commit = commit(binaryParentDiffs(path, status, 9, List.of("testtest")));
            var message = message(commit);
            var check = new BinaryCheck();
            var issues = toList(check.check(commit, message, conf, null));
            assertEquals(1, issues.size());
            assertTrue(issues.get(0) instanceof BinaryFileTooLargeIssue);
            var issue = (BinaryFileTooLargeIssue) issues.get(0);
            assertEquals(path, issue.path());
            assertEquals(9, issue.fileSize());
            assertEquals(1, issue.limitedFileSize());
            assertEquals(commit, issue.commit());
            assertEquals(message, issue.message());
            assertEquals(check, issue.check());
            assertEquals(Severity.ERROR, issue.severity());
        }
        Files.deleteIfExists(path);
    }
}
