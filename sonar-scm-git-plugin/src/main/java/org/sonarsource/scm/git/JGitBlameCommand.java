/*
 * SonarQube :: Plugins :: SCM :: Git
 * Copyright (C) 2014-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonarsource.scm.git;

import com.google.common.annotations.VisibleForTesting;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.scm.BlameCommand;
import org.sonar.api.batch.scm.BlameLine;
import org.sonar.api.scan.filesystem.PathResolver;
import org.sonar.api.utils.MessageException;

public class JGitBlameCommand extends BlameCommand {

  private static final Logger LOG = LoggerFactory.getLogger(JGitBlameCommand.class);

  private final PathResolver pathResolver;

  public JGitBlameCommand(PathResolver pathResolver) {
    this.pathResolver = pathResolver;
  }

  @Override
  public void blame(BlameInput input, BlameOutput output) {
    File basedir = input.fileSystem().baseDir();
    Repository repo = buildRepository(basedir);
    try {
      Git git = Git.wrap(repo);
      File gitBaseDir = repo.getWorkTree();
      ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
      List<Future<Void>> tasks = submitTasks(input, output, git, gitBaseDir, executorService);
      executorService.shutdown();
      waitForTaskToComplete(tasks);
    } finally {
      repo.close();
    }
  }

  @VisibleForTesting
  static void waitForTaskToComplete(List<Future<Void>> tasks) {
    for (Future<Void> task : tasks) {
      try {
        task.get();
      } catch (ExecutionException e) {
        // Unwrap ExecutionException
        throw e.getCause() instanceof RuntimeException ? (RuntimeException) e.getCause() : new IllegalStateException(e.getCause());
      } catch (InterruptedException e) {
        LOG.warn("Process was interrupted", e);
        tasks.forEach(t -> t.cancel(true));
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private List<Future<Void>> submitTasks(BlameInput input, BlameOutput output, Git git, File gitBaseDir, ExecutorService executorService) {
    List<Future<Void>> tasks = new ArrayList<>();
    for (InputFile inputFile : input.filesToBlame()) {
      tasks.add(submitTask(output, git, gitBaseDir, inputFile, executorService));
    }
    return tasks;
  }

  private static Repository buildRepository(File basedir) {
    RepositoryBuilder repoBuilder = new RepositoryBuilder()
      .findGitDir(basedir)
      .setMustExist(true);
    if (repoBuilder.getGitDir() == null) {
      throw MessageException.of(basedir + " doesn't seem to be contained in a Git repository");
    }
    try {
      Repository repo = repoBuilder.build();
      // SONARSCGIT-2 Force initialization of shallow commits to avoid later concurrent modification issue
      repo.getObjectDatabase().newReader().getShallowCommits();
      return repo;
    } catch (IOException e) {
      throw new IllegalStateException("Unable to open Git repository", e);
    }
  }

  private Future<Void> submitTask(final BlameOutput output, final Git git, final File gitBaseDir, final InputFile inputFile, ExecutorService executorService) {
    return executorService.submit(new Callable<Void>() {
      @Override
      public Void call() throws GitAPIException {
        blame(output, git, gitBaseDir, inputFile);
        return null;
      }
    });
  }

  private void blame(BlameOutput output, Git git, File gitBaseDir, InputFile inputFile) throws GitAPIException {
    String filename = pathResolver.relativePath(gitBaseDir, inputFile.file());
    LOG.debug("Blame file {}", filename);
    org.eclipse.jgit.blame.BlameResult blameResult;
    try {
      blameResult = git.blame()
        // Equivalent to -w command line option
        .setTextComparator(RawTextComparator.WS_IGNORE_ALL)
        .setFilePath(filename).call();
    } catch (Exception e) {
      throw new IllegalStateException("Unable to blame file " + inputFile.relativePath(), e);
    }
    List<BlameLine> lines = new ArrayList<>();
    if (blameResult == null) {
      LOG.debug("Unable to blame file {}. It is probably a symlink.", inputFile.relativePath());
      return;
    }
    for (int i = 0; i < blameResult.getResultContents().size(); i++) {
      if (blameResult.getSourceAuthor(i) == null || blameResult.getSourceCommit(i) == null) {
        LOG.debug("Unable to blame file {}. No blame info at line {}. Is file committed? [Author: {} Source commit: {}]", inputFile.relativePath(), i + 1,
          blameResult.getSourceAuthor(i), blameResult.getSourceCommit(i));
        return;
      }
      lines.add(new org.sonar.api.batch.scm.BlameLine()
        .date(blameResult.getSourceCommitter(i).getWhen())
        .revision(blameResult.getSourceCommit(i).getName())
        .author(blameResult.getSourceAuthor(i).getEmailAddress()));
    }
    if (lines.size() == inputFile.lines() - 1) {
      // SONARPLUGINS-3097 Git do not report blame on last empty line
      lines.add(lines.get(lines.size() - 1));
    }
    output.blameResult(inputFile, lines);
  }

}
