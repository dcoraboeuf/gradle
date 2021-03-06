/*
 * Copyright 2014 the original author or authors.
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

package org.gradle.play.internal.run;

import org.gradle.api.Action;
import org.gradle.api.logging.Logging;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.classpath.DefaultClassPath;
import org.gradle.process.internal.WorkerProcessContext;

import java.io.IOException;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.util.concurrent.CountDownLatch;

public class PlayWorkerServer implements Action<WorkerProcessContext>, PlayRunWorkerServerProtocol, Serializable {

    private PlayRunSpec runSpec;
    private VersionedPlayRunAdapter runAdapter;

    private volatile CountDownLatch stop;

    public PlayWorkerServer(PlayRunSpec runSpec, VersionedPlayRunAdapter runAdapter) {
        this.runSpec = runSpec;
        this.runAdapter = runAdapter;
    }

    public void execute(WorkerProcessContext context) {
        stop = new CountDownLatch(1);
        final PlayRunWorkerClientProtocol clientProtocol = context.getServerConnection().addOutgoing(PlayRunWorkerClientProtocol.class);
        context.getServerConnection().addIncoming(PlayRunWorkerServerProtocol.class, this);
        context.getServerConnection().connect();
        final PlayAppLifecycleUpdate result = startServer();
        try {
            clientProtocol.update(result);
            stop.await();
        } catch (InterruptedException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        } finally {
            clientProtocol.update(PlayAppLifecycleUpdate.stopped());
        }
    }

    private PlayAppLifecycleUpdate startServer() {
        try {
            run();
            return PlayAppLifecycleUpdate.running();
        } catch (Exception e) {
            Logging.getLogger(this.getClass()).error("Failed to run Play", e);
            return PlayAppLifecycleUpdate.failed(e);
        }
    }

    private void run() {
        disableUrlConnectionCaching();
        final Thread thread = Thread.currentThread();
        final ClassLoader previousContextClassLoader = thread.getContextClassLoader();
        final ClassLoader classLoader = new URLClassLoader(new DefaultClassPath(runSpec.getClasspath()).getAsURLArray());
        thread.setContextClassLoader(classLoader);
        try {
            ClassLoader docsClassLoader = classLoader;

            Object buildDocHandler = runAdapter.getBuildDocHandler(docsClassLoader, runSpec.getClasspath());

            Object buildLink = runAdapter.getBuildLink(classLoader, runSpec.getProjectPath(), runSpec.getApplicationJar(), runSpec.getChangingClasspath(), runSpec.getAssetsJar(), runSpec.getAssetsDirs());
            runAdapter.runDevHttpServer(classLoader, docsClassLoader, buildLink, buildDocHandler, runSpec.getHttpPort());
        } catch (Exception e) {
            throw UncheckedException.throwAsUncheckedException(e);
        } finally {
            thread.setContextClassLoader(previousContextClassLoader);
        }
    }

    private void disableUrlConnectionCaching() {
        // fix problems in updating jar files by disabling default caching of URL connections.
        // URLConnection default caching should be disabled since it causes jar file locking issues and JVM crashes in updating jar files.
        // Changes to jar files won't be noticed in all cases when caching is enabled.
        // sun.net.www.protocol.jar.JarURLConnection leaves the JarFile instance open if URLConnection caching is enabled.
        try {
            URL url = new URL("jar:file://valid_jar_url_syntax.jar!/");
            URLConnection urlConnection = url.openConnection();
            urlConnection.setDefaultUseCaches(false);
        } catch (MalformedURLException e) {
            UncheckedException.throwAsUncheckedException(e);
        } catch (IOException e) {
            UncheckedException.throwAsUncheckedException(e);
        }
    }

    public void stop() {
        stop.countDown();
    }

    @Override
    public void rebuildSuccess() {
        runAdapter.forceReloadNextTime();
    }
}
