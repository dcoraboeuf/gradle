/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.play.integtest.fixtures

import org.gradle.launcher.continuous.Java7RequiringContinuousIntegrationTest
import org.gradle.play.integtest.fixtures.app.PlayApp
import org.gradle.test.fixtures.file.TestFile

abstract class AbstractPlayContinuousBuildIntegrationTest extends Java7RequiringContinuousIntegrationTest {
    abstract PlayApp getPlayApp()
    abstract RunningPlayApp getRunningApp()

    def setup() {
        writeSources()
        buildTimeout = 90
    }

    TestFile getPlayRunBuildFile() {
        buildFile
    }

    def writeSources() {
        playApp.writeSources(testDirectory)

        playRunBuildFile << """
            model {
                tasks.runPlayBinary {
                    httpPort = ${runningApp.selectPort()}
                }
            }
        """

        settingsFile << """
            rootProject.name = '${playApp.name}'
        """
    }

    void appIsRunningAndDeployed() {
        runningApp.verifyStarted()
        runningApp.verifyContent()
    }

    void appIsStopped() {
        runningApp.verifyStopped()
    }
}
