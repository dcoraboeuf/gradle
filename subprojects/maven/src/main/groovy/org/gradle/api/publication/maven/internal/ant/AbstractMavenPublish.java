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

package org.gradle.api.publication.maven.internal.ant;

import com.beust.jcommander.internal.Lists;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.artifact.manager.WagonManager;
import org.apache.maven.artifact.metadata.ArtifactMetadata;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.DefaultArtifactRepository;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.project.artifact.AttachedArtifact;
import org.apache.maven.project.artifact.ProjectArtifactMetadata;
import org.apache.tools.ant.BuildException;
import org.codehaus.classworlds.ClassWorld;
import org.codehaus.classworlds.DuplicateRealmException;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.PlexusContainerException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.embed.Embedder;
import org.gradle.api.GradleException;

import java.io.File;
import java.util.List;

abstract class AbstractMavenPublish implements MavenPublishSupport {
    private static ClassLoader plexusClassLoader;

    private final File pomFile;
    private final List<AdditionalArtifact> additionalArtifacts = Lists.newArrayList();
    private File mainArtifact;

    private File localMavenRepository;
    private PlexusContainer container;

    protected AbstractMavenPublish(File pomFile) {
        this.pomFile = pomFile;

        WagonManager wagonManager = (WagonManager) lookup(WagonManager.ROLE);
        wagonManager.setDownloadMonitor(new LoggingMavenTransferListener());
    }

    public void setLocalMavenRepositoryLocation(File localMavenRepository) {
        this.localMavenRepository = localMavenRepository;
    }

    public void setMainArtifact(File file) {
        this.mainArtifact = file;
    }

    @Override
    public void addAdditionalArtifact(File file, String type, String classifier) {
        AdditionalArtifact artifact = new AdditionalArtifact();
        artifact.setFile(file);
        artifact.setType(type);
        artifact.setClassifier(classifier);

        additionalArtifacts.add(artifact);
    }

    public void publish() {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            if (plexusClassLoader != null) {
                Thread.currentThread().setContextClassLoader(plexusClassLoader);
            }
            doPublish();
        } finally {
            plexusClassLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
    }

    private void doPublish() {
        ArtifactRepository localRepo = createLocalArtifactRepository();
        ParsedMavenPom parsedMavenPom = new ParsedMavenPom(pomFile);

        Artifact parentArtifact;
        if (mainArtifact == null) {
            Artifact pomArtifact = createPomArtifact(parsedMavenPom);
            publishArtifact(pomArtifact, pomFile, localRepo);
            parentArtifact = pomArtifact;
        } else {
            Artifact artifact = createMainArtifact(parsedMavenPom);
            ArtifactMetadata metadata = new ProjectArtifactMetadata(artifact, pomFile);
            artifact.addMetadata(metadata);
            publishArtifact(artifact, mainArtifact, localRepo);
            parentArtifact = artifact;
        }

        for (AdditionalArtifact attachedArtifact : additionalArtifacts) {
            Artifact attach = createAttachedArtifact(parentArtifact, attachedArtifact.getType(), attachedArtifact.getClassifier());
            publishArtifact(attach, attachedArtifact.getFile(), localRepo);
        }
    }

    protected abstract void publishArtifact(Artifact artifact, File artifactFile, ArtifactRepository localRepo);

    private ArtifactRepository createLocalArtifactRepository() {
        ArtifactRepositoryLayout repositoryLayout = (ArtifactRepositoryLayout) lookup(ArtifactRepositoryLayout.ROLE, "default");
        String localRepositoryLocation = localMavenRepository.toURI().toString();
        return new DefaultArtifactRepository("local", localRepositoryLocation, repositoryLayout);
    }

    protected Object lookup(String role) {
        try {
            return getContainer().lookup(role);
        } catch (ComponentLookupException e) {
            throw new GradleException("Unable to find component: " + role, e);
        }
    }

    protected Object lookup(String role, String roleHint) {
        try {
            return getContainer().lookup(role, roleHint);
        } catch (ComponentLookupException e) {
            throw new GradleException("Unable to find component: " + role + "[" + roleHint + "]", e);
        }
    }

    protected synchronized PlexusContainer getContainer() {
        if (container == null) {
            try {
                ClassWorld classWorld = new ClassWorld();

                classWorld.newRealm("plexus.core", getClass().getClassLoader());

                Embedder embedder = new Embedder();

                embedder.start(classWorld);

                container = embedder.getContainer();
            } catch (PlexusContainerException e) {
                throw new BuildException("Unable to start embedder", e);
            } catch (DuplicateRealmException e) {
                throw new BuildException("Unable to create embedder ClassRealm", e);
            }
        }

        return container;
    }

    private Artifact createMainArtifact(ParsedMavenPom pom) {
        return new DefaultArtifact(pom.getGroup(), pom.getArtifactId(), VersionRange.createFromVersion(pom.getVersion()),
                null, pom.getPackaging(), null, artifactHandler(pom.getPackaging()));
    }

    private Artifact createPomArtifact(ParsedMavenPom pom) {
        return new DefaultArtifact(pom.getGroup(), pom.getArtifactId(), VersionRange.createFromVersion(pom.getVersion()),
                null, "pom", null, artifactHandler("pom"));
    }

    private Artifact createAttachedArtifact(Artifact mainArtifact, String type, String classifier) {
        return new AttachedArtifact(mainArtifact, type, classifier, artifactHandler(type));
    }

    private DefaultArtifactHandler artifactHandler(String type) {
        return new DefaultArtifactHandler(type);
    }

    private static class AdditionalArtifact {
        File file;
        String type;
        String classifier;

        public void setFile(File file) {
            this.file = file;
        }

        public File getFile() {
            return file;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getType() {
            return type;
        }

        public void setClassifier(String classifier) {
            this.classifier = classifier;
        }

        public String getClassifier() {
            return classifier;
        }
    }

}
