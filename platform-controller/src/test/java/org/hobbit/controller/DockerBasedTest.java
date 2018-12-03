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
package org.hobbit.controller;

import java.util.List;

import com.spotify.docker.client.exceptions.DockerException;
import org.apache.commons.compress.utils.IOUtils;
import org.hobbit.core.Constants;
import org.hobbit.utils.docker.DockerHelper;
import org.junit.After;
import org.junit.Before;

import com.spotify.docker.client.DefaultDockerClient;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.messages.Image;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.EnvironmentVariables;

/**
 * Created by Timofey Ermilov on 02/09/16.
 */
public class DockerBasedTest {
    protected DockerClient dockerClient;
    protected static final String busyboxImageName = "busybox:latest";
    protected static final String benchmarkImageName = "git.project-hobbit.eu:4567/smirnp/sml-benchmark-v2/benchmark-controller";

    protected static final String[] sleepCommand = { "sleep", "60s" };

    @Rule
    public final EnvironmentVariables environmentVariables = new EnvironmentVariables();

    protected boolean findImageWithTag(final String id, final List<Image> images) {
        if (images != null) {
            for (Image image : images) {
                if (image.repoTags() != null) {
                    for (String tag : image.repoTags()) {
                        if (tag.contains(id)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    @Before
    public void initClient() throws Exception {



        dockerClient = DefaultDockerClient.fromEnv().build();

    }

    @Test
    public void pullImageTest() throws DockerException, InterruptedException {
        // check if busybox is present
        List<Image> images = dockerClient.listImages(DockerClient.ListImagesParam.allImages());
        String imageName = benchmarkImageName;
        if (!findImageWithTag(imageName, images)) {
            dockerClient.pull(imageName);

        }
    }


    @After
    public void close() throws Exception {
        IOUtils.closeQuietly(dockerClient);
    }
}
