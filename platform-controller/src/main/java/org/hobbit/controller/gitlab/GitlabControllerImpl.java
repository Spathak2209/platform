/**
 * This file is part of platform-controller.
 *
 * platform-controller is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * platform-controller is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with platform-controller.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.hobbit.controller.gitlab;

import java.io.FileNotFoundException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.commons.io.Charsets;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.gitlab.api.GitlabAPI;
import org.gitlab.api.models.GitlabBranch;
import org.gitlab.api.models.GitlabProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by Timofey Ermilov on 17/10/2016.
 */
public class GitlabControllerImpl implements GitlabController {
    private static final Logger LOGGER = LoggerFactory.getLogger(GitlabControllerImpl.class);

    public static final String GITLAB_URL_KEY = "GITLAB_URL";
    public static final String GITLAB_TOKEN_KEY = "GITLAB_TOKEN";

    // Gitlab URL and access token
    private static final String GITLAB_URL = System.getProperty(GITLAB_URL_KEY, "https://git.project-hobbit.eu/");
    private static final String GITLAB_TOKEN = System.getenv(GITLAB_TOKEN_KEY);
    private static final String GITLAB_DEFAULT_GUEST_TOKEN = "fykySfxWaUyCS1xxTSVy";

    // HobbitConfig filenames
    private static final String SYSTEM_CONFIG_FILENAME = "system.ttl";
    private static final String BENCHMARK_CONFIG_FILENAME = "benchmark.ttl";

    private static final int MAX_PARSING_ERRORS = 50;

    // gitlab api
    private GitlabAPI api;
    // projects refresh timer
    private Timer timer;
    private int repeatInterval = 60 * 1000; // every 1 min
    private boolean projectsFetched = false; // indicates whether projects was
                                             // fetched first time
    private List<Runnable> readyRunnable;
    // projects array
    private List<Project> projects;
    private Set<String> parsingErrors = new HashSet<String>();
    private Deque<String> sortedParsingErrors = new LinkedList<String>();

    public GitlabControllerImpl() {
        String token = GITLAB_TOKEN;
        if (token == null || token.isEmpty()) {
            // use default "guest" token, to use openly available projects
            token = GITLAB_DEFAULT_GUEST_TOKEN;
        }
        api = GitlabAPI.connect(GITLAB_URL, token);
        timer = new Timer();
        projects = new ArrayList<>();
        readyRunnable = new ArrayList<>();

        // start fetching projects
        startFetchingProjects();
    }

    public void runAfterFirstFetch(Runnable r) {
        readyRunnable.add(r);
    }

    private void startFetchingProjects() {
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    List<Project> newProjects = new ArrayList<>();

                    try {
                        // Get all projects visible to the user
                        List<GitlabProject> gitProjects;
                        if (api.getUser().isAdmin()) {
                            // Get all Projects as Sudo, as "visible" is
                            // restricted
                            // even though user has sudo access
                            gitProjects = api.getAllProjects();
                        } else {
                            // If the user does not have sudo access use all the
                            // visible projects.
                            gitProjects = api.retrieve().getAll("/projects/visible", GitlabProject[].class);
                        }
                        LOGGER.info("Projects: " + gitProjects.size());
                        for (GitlabProject project : gitProjects) {
                            try {
                                Project p = gitlabToProject(project);
                                if (p != null) {
                                    newProjects.add(p);
                                }
                            } catch (Exception e) {
                                LOGGER.error("Error getting project config files", e);
                            }
                        }
                    } catch (Exception | Error e) {
                        LOGGER.error("Couldn't get all gitlab projects.", e);
                    }

                    if (projects == null) {
                        // This is the first fetching of projects -> we might
                        // have
                        // to notify threads that are waiting for that
                        projects = newProjects;
                        synchronized (this) {
                            this.notifyAll();
                        }
                    } else {
                        // update cached version
                        projects = newProjects;
                    }
                    // indicate that projects were fetched
                    if (!projectsFetched) {
                        projectsFetched = true;
                        for (Runnable r : readyRunnable) {
                            r.run();
                        }
                    }
                } catch (Throwable t) {
                    LOGGER.error("Got an uncatched throwable.", t);
                }
            }
        }, 0, repeatInterval);
    }

    @Override
    public void stopFetchingProjects() {
        timer.cancel();
        timer.purge();
    }

    @Override
    public List<Project> getAllProjects() {
        if (projects == null) {
            // The projects don't have been fetched -> we should wait until this
            // has been done but with a maximum of 10 seconds.
            synchronized (this) {
                try {
                    this.wait(10000);
                } catch (InterruptedException e) {
                }
            }
        }
        return projects;
    }

    private Project gitlabToProject(GitlabProject project) throws Exception {
        // get default branch
        GitlabBranch b;
        try {
            b = api.getBranch(project, project.getDefaultBranch());
        } catch (Exception e) {
            // there is no default graph -> the project is empty
            // we can return null and don't have to log this error
            return null;
        }
        // read system config
        String systemCfgContent = null;
        try {
            byte[] systemCfgBytes = api.getRawFileContent(project, b.getCommit().getId(), SYSTEM_CONFIG_FILENAME);
            systemCfgContent = getCheckedModelString(new String(systemCfgBytes, Charsets.UTF_8), "system",
                    project.getWebUrl());
        } catch (FileNotFoundException e) {
            LOGGER.debug("No config files found in", project.getWebUrl());
        }
        // read benchmark config
        String benchmarkCfgContent = null;
        try {
            byte[] benchmarkCfgBytes = api.getRawFileContent(project, b.getCommit().getId(), BENCHMARK_CONFIG_FILENAME);
            benchmarkCfgContent = getCheckedModelString(new String(benchmarkCfgBytes, Charsets.UTF_8), "benchmark",
                    project.getWebUrl());
        } catch (FileNotFoundException e) {
            LOGGER.debug("No config files found in", project.getWebUrl());
        }
        if ((benchmarkCfgContent != null) || (systemCfgContent != null)) {
            // get user
            String user = project.getOwner().getUsername();
            Project p = new Project(benchmarkCfgContent, systemCfgContent, user, project.getName());
            return p;
        } else {
            return null;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        GitlabControllerImpl c = new GitlabControllerImpl();
        Thread.sleep(3000);
        List<Project> projects = c.getAllProjects();
        for (Project p : projects) {
            System.out.println(p);
        }
    }

    protected String getCheckedModelString(String modelString, String modelType, String projectName) {
        try {
            Model model = ModelFactory.createDefaultModel();
            model.read(new StringReader(modelString), null, "TTL");
            return modelString;
        } catch (Exception e) {
            String parsingError = "Couldn't parse " + modelType + " model from " + projectName
                    + ". It won't be available. " + e.getMessage();
            if (parsingErrors.contains(parsingError)) {
                LOGGER.info(parsingError + " (Error already reported before)");
            } else {
                LOGGER.info("Couldn't parse " + modelType + " model from " + projectName + ". It won't be available.",
                        e);
                sortedParsingErrors.addLast(parsingError);
                parsingErrors.add(parsingError);
                // If the cached errors become to long, remove the oldest
                if (sortedParsingErrors.size() > MAX_PARSING_ERRORS) {
                    parsingErrors.remove(sortedParsingErrors.pop());
                }
            }
        }
        return null;
    }
}
