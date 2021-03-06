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

package org.gradle.testkit

import org.gradle.api.internal.GradleDistributionLocator
import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.daemon.DaemonLogsAnalyzer
import org.gradle.test.fixtures.file.TestFile
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.internal.IsolatedDaemonHomeTmpDirectoryProvider
import org.gradle.util.GFileUtils

class TestKitEndUserIntegrationTest extends AbstractIntegrationSpec {
    DaemonLogsAnalyzer daemonLogsAnalyzer

    def setup() {
        executer.requireGradleHome().withStackTraceChecksDisabled()
        buildFile << buildFileForGroovyProject()
        daemonLogsAnalyzer = createDaemonLogAnalyzer()
    }

    def "use of GradleRunner API in test class without declaring test-kit dependency causes compilation error"() {
        given:
        writeTest(buildLogicFunctionalTestCreatingGradleRunner())

        when:
        fails('build')

        then:
        executedAndNotSkipped(':compileTestGroovy')
        failureHasCause('Compilation failed; see the compiler error output for details.')
        failure.error.contains("unable to resolve class $GradleRunner.name")
    }

    private TestFile writeTest(String content) {
        testDirectoryProvider.file("src/test/groovy/org/gradle/test/BuildLogicFunctionalTest.groovy") << content
    }

    def "use of GradleRunner API in test class by depending on external test-kit dependency causes compilation error"() {
        File gradleDistPluginsDir = new File(distribution.gradleHomeDir, 'lib/plugins')
        File[] gradleTestKitLibs = gradleDistPluginsDir.listFiles(new GradleTestKitJarFilenameFilter())
        assert gradleTestKitLibs.length == 1
        File gradleTestKitLib = gradleTestKitLibs[0]
        TestFile libDir = testDirectoryProvider.createDir('lib')
        GFileUtils.copyFile(gradleTestKitLib, new File(libDir, gradleTestKitLib.name))

        buildFile << libDirDependency()
        writeTest(buildLogicFunctionalTestCreatingGradleRunner())

        when:
        fails('build')

        then:
        executedAndNotSkipped(':compileTestGroovy')
        failure.error.contains("Unable to load class $GradleRunner.name due to missing dependency ${GradleDistributionLocator.name.replaceAll('\\.', '/')}")
    }

    def "creating GradleRunner instance by depending on Gradle libraries outside of Gradle distribution throws exception"() {
        File gradleDistLibDir = new File(distribution.gradleHomeDir, 'lib')
        File gradleDistPluginsDir = new File(gradleDistLibDir, 'plugins')
        File[] gradleCoreLibs = gradleDistLibDir.listFiles(new GradleCoreJarFilenameFilter())
        File[] gradleTestKitLibs = gradleDistPluginsDir.listFiles(new GradleTestKitJarFilenameFilter())
        assert gradleCoreLibs.length == 3
        assert gradleTestKitLibs.length == 1
        File[] allGradleLibs = gradleCoreLibs + gradleTestKitLibs
        TestFile libDir = testDirectoryProvider.createDir('lib')

        allGradleLibs.each {
            GFileUtils.copyFile(it, new File(libDir, it.name))
        }

        buildFile << libDirDependency()
        writeTest(buildLogicFunctionalTestCreatingGradleRunner())

        when:
        fails('build')

        then:
        executedAndNotSkipped(':test')
        failure.output.contains('java.lang.IllegalStateException: Could not create a GradleRunner, as the GradleRunner class was not loaded from a Gradle distribution')
    }

    def "successfully execute functional test and verify expected result"() {
        buildFile << gradleTestKitDependency()
        writeTest """
            package org.gradle.test

            import org.gradle.testkit.runner.GradleRunner
            import static org.gradle.testkit.runner.TaskOutcome.*
            import org.junit.Rule
            import org.junit.rules.TemporaryFolder
            import spock.lang.Specification

            class BuildLogicFunctionalTest extends Specification {
                @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
                File buildFile

                def setup() {
                    buildFile = testProjectDir.newFile('build.gradle')
                }

                def "execute helloWorld task"() {
                    given:
                    buildFile << '''
                        task helloWorld {
                            doLast {
                                println 'Hello world!'
                            }
                        }
                    '''

                    when:
                    def result = GradleRunner.create()
                        .withProjectDir(testProjectDir.root)
                        .withArguments('helloWorld')
                        .build()

                    then:
                    result.standardOutput.contains('Hello world!')
                    result.standardError == ''
                    result.taskPaths(SUCCESS) == [':helloWorld']
                    result.taskPaths(SKIPPED).empty
                    result.taskPaths(UP_TO_DATE).empty
                    result.taskPaths(FAILED).empty
                }
            }
        """

        when:
        succeeds('build')

        then:
        executedAndNotSkipped(":test", ":build")
        daemonLogsAnalyzer.visible.empty
    }

    def "functional test fails due to invalid JVM parameter for test execution"() {
        buildFile << gradleTestKitDependency()
        writeTest """
            package org.gradle.test

            import org.gradle.testkit.runner.GradleRunner
            import static org.gradle.testkit.runner.TaskOutcome.*
            import org.junit.Rule
            import org.junit.rules.TemporaryFolder
            import spock.lang.Specification

            class BuildLogicFunctionalTest extends Specification {
                @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
                File buildFile

                def setup() {
                    buildFile = testProjectDir.newFile('build.gradle')
                    new File(testProjectDir.root, 'gradle.properties') << 'org.gradle.jvmargs=-unknown'
                }

                def "execute helloWorld task"() {
                    given:
                    buildFile << '''
                        task helloWorld {
                            doLast {
                                println 'Hello world!'
                            }
                        }
                    '''

                    expect:
                    GradleRunner.create()
                        .withProjectDir(testProjectDir.root)
                        .withArguments('helloWorld')
                        .build()
                }
            }
        """

        when:
        fails('build')

        then:
        failureDescriptionContains("Execution failed for task ':test'.")
        failure.output.contains('Unrecognized option: -unknown')
        daemonLogsAnalyzer.visible.empty
    }

    def "can test plugin and custom task as external files by adding them to the build script's classpath"() {
        file("settings.gradle") << "include 'sub'"
        file("sub/build.gradle") << "apply plugin: 'groovy'; dependencies { compile localGroovy() }"
        file("sub/src/main/groovy/org/gradle/test/lib/Support.groovy") << "package org.gradle.test.lib; class Support { static String MSG = 'Hello world!' }"

        buildFile <<
            gradleApiDependency() <<
            gradleTestKitDependency() <<
            """
                dependencies {
                  compile project(":sub")
                }

                task createClasspathManifest {
                    def outputDir = file("\$buildDir/\$name")

                    inputs.files sourceSets.main.runtimeClasspath
                    outputs.dir outputDir

                    doLast {
                        outputDir.mkdirs()
                        file("\$outputDir/plugin-classpath.txt").text = sourceSets.main.runtimeClasspath.join("\\n")
                    }
                }

                dependencies {
                    testCompile files(createClasspathManifest)
                }
            """

        file("src/main/groovy/org/gradle/test/HelloWorldPlugin.groovy") << """
            package org.gradle.test

            import org.gradle.api.Plugin
            import org.gradle.api.Project

            class HelloWorldPlugin implements Plugin<Project> {
                void apply(Project project) {
                    project.task('helloWorld', type: HelloWorld)
                }
            }
        """

        file("src/main/groovy/org/gradle/test/HelloWorld.groovy") << """
            package org.gradle.test

            import org.gradle.api.DefaultTask
            import org.gradle.api.tasks.TaskAction
            import org.gradle.test.lib.Support

            class HelloWorld extends DefaultTask {
                @TaskAction
                void doSomething() {
                    println Support.MSG
                }
            }
        """

        writeTest """
            package org.gradle.test

            import org.gradle.testkit.runner.GradleRunner
            import static org.gradle.testkit.runner.TaskOutcome.*
            import org.junit.Rule
            import org.junit.rules.TemporaryFolder
            import spock.lang.Specification

            class BuildLogicFunctionalTest extends Specification {
                @Rule final TemporaryFolder testProjectDir = new TemporaryFolder()
                File buildFile

                def setup() {
                    buildFile = testProjectDir.newFile('build.gradle')
                    def pluginClasspath = getClass().classLoader.findResource("plugin-classpath.txt")
                      .readLines()
                      .collect { it.replace('\\\\', '\\\\\\\\') } // escape backslashes in Windows paths
                      .collect { "'\$it'" }
                      .join(", ")

                    buildFile << \"\"\"
                        buildscript {
                            dependencies {
                                classpath files(\$pluginClasspath)
                            }
                        }
                    \"\"\"
                }

                def "execute helloWorld task"() {
                    given:
                    buildFile << 'apply plugin: org.gradle.test.HelloWorldPlugin'

                    when:
                    def result = GradleRunner.create()
                        .withProjectDir(testProjectDir.root)
                        .withArguments('helloWorld')
                        .build()

                    then:
                    result.standardOutput.contains('Hello world!')
                    result.standardError == ''
                    result.taskPaths(SUCCESS) == [':helloWorld']
                    result.taskPaths(SKIPPED).empty
                    result.taskPaths(UP_TO_DATE).empty
                    result.taskPaths(FAILED).empty
                }
            }
        """

        when:
        succeeds('build')

        then:
        executedAndNotSkipped(':test')
        daemonLogsAnalyzer.visible.empty
    }

    private DaemonLogsAnalyzer createDaemonLogAnalyzer() {
        File gradleUserHomeDir = new IsolatedDaemonHomeTmpDirectoryProvider().createDir()
        File daemonBaseDir = new File(gradleUserHomeDir, 'daemon')
        DaemonLogsAnalyzer.newAnalyzer(daemonBaseDir, executer.distribution.version.version)
    }

    private static String buildFileForGroovyProject() {
        """
            apply plugin: 'groovy'

            dependencies {
                compile localGroovy()
                testCompile 'org.spockframework:spock-core:1.0-groovy-2.3'
            }

            repositories {
                mavenCentral()
            }

            test.testLogging.exceptionFormat = 'full'
        """
    }

    private static String gradleTestKitDependency() {
        """
            dependencies {
                testCompile gradleTestKit()
            }
        """
    }

    private static String gradleApiDependency() {
        """
            dependencies {
                compile gradleApi()
            }
        """
    }

    private static String libDirDependency() {
        """
            dependencies {
                testCompile fileTree(dir: 'lib', include: '*.jar')
            }
        """
    }

    private static String buildLogicFunctionalTestCreatingGradleRunner() {
        """
            package org.gradle.test

            import org.gradle.testkit.runner.GradleRunner
            import spock.lang.Specification

            class BuildLogicFunctionalTest extends Specification {
                def "create GradleRunner"() {
                    expect:
                    GradleRunner.create()
                }
            }
        """
    }

    private class GradleTestKitJarFilenameFilter implements FilenameFilter {
        boolean accept(File dir, String name) {
            name.startsWith('gradle-test-kit')
        }
    }

    private class GradleCoreJarFilenameFilter implements FilenameFilter {
        boolean accept(File dir, String name) {
            name.startsWith('gradle-core') || name.startsWith('gradle-base-services')
        }
    }
}
