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

package org.gradle.tooling.internal.provider.runner;

import org.gradle.internal.invocation.BuildActionRunner;
import org.gradle.internal.service.ServiceRegistration;
import org.gradle.internal.service.scopes.PluginServiceRegistry;

public class ToolingBuilderServices implements PluginServiceRegistry {
    @Override
    public void registerGlobalServices(ServiceRegistration registration) {

        registration.addProvider(new Object() {
            BuildActionRunner createBuildModelActionRunner(BuildClientSubscriptionHandler buildClientSubscriptionHandler) {
                return new SubscribableBuildActionRunner(buildClientSubscriptionHandler, new BuildModelActionRunner());
            }

            BuildActionRunner createTestExecutionRequestActionRunner(BuildClientSubscriptionHandler buildClientSubscriptionHandler) {
                return new SubscribableBuildActionRunner(buildClientSubscriptionHandler, new TestExecutionRequestActionRunner());
            }

            BuildActionRunner createClientProvidedBuildActionRunner(BuildClientSubscriptionHandler buildClientSubscriptionHandler) {
                return new SubscribableBuildActionRunner(buildClientSubscriptionHandler, new ClientProvidedBuildActionRunner());
            }

            BuildClientSubscriptionHandler createBuildClientSubscriptionHandler() {
                return new BuildClientSubscriptionHandler();
            }
        });
    }

    public void registerBuildSessionServices(ServiceRegistration registration) {
    }

    @Override
    public void registerBuildServices(ServiceRegistration registration) {
    }

    public void registerGradleServices(ServiceRegistration registration) {
    }

    @Override
    public void registerProjectServices(ServiceRegistration registration) {
    }
}
