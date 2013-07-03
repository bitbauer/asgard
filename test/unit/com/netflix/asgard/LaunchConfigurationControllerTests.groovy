/*
 * Copyright 2012 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.asgard

import com.netflix.asgard.mock.Mocks
import org.junit.Before

class LaunchConfigurationControllerTests {

    @Before
    void setUp() {
        Mocks.createDynamicMethods() 
        TestUtils.setUpMockRequest()
        controller.awsAutoScalingService = Mocks.awsAutoScalingService()
        controller.applicationService = Mocks.applicationService()
        controller.awsEc2Service = Mocks.awsEc2Service()
    }

    void testShow() {
        def p = controller.params
        p.name = 'helloworld-example-v015-20111014165240'
        def attrs = controller.show()
        assert 'helloworld-example-v015-20111014165240' == attrs.lc.launchConfigurationName
        assert 'helloworld-example-v015' == attrs.group.autoScalingGroupName
        assert 'helloworld' == attrs.app.name
        assert 'ami-4775b32e' == attrs.image.imageId
    }

    void testShowNonExistent() {
        def p = controller.params
        p.name ='doesntexist'
        controller.show()
        assert '/error/missing' == view
        assert "Launch Configuration 'doesntexist' not found in us-east-1 test" == controller.flash.message
    }
	
	void testUpdateWithoutMostParams() {
		request.method = 'POST'
		def p = controller.params
		p.appName = 'helloworld'
		def cmd = new LoadBalancerCreateCommand(appName: p.appName)
		cmd.applicationService = Mocks.applicationService()
		cmd.validate()
		assert cmd.hasErrors()
		controller.update(cmd)
		assert response.redirectUrl == '/loadBalancer/create?appName=helloworld'
		assert flash.chainModel.cmd == cmd
	}

	void testUpdateSuccessfully() {
		request.method = 'POST'
		def p = controller.params
		p.appName = 'helloworld'
		p.stack = 'unittest'
		p.detail ='frontend'
		p.protocol1 = 'HTTP'
		p.lbPort1 = '80'
		p.instancePort1 = '7001'
		p.target = 'HTTP:7001/healthcheck'
		p.interval = '40'
		p.timeout = '40'
		p.unhealthy = '40'
		p.healthy = '40'
		LoadBalancerCreateCommand cmd = new LoadBalancerCreateCommand(appName: p.appName, stack: p.stack,
				detail: p.detail, protocol1: p.protocol1, lbPort1: p.lbPort1 as Integer,
				instancePort1: p.instancePort1 as Integer, target: p.target, interval: p.interval as Integer,
				timeout: p.timeout as Integer, unhealthy: p.unhealthy as Integer, healthy: p.healthy as Integer)
		cmd.applicationService = Mocks.applicationService()
		cmd.validate()
		assert !cmd.hasErrors()
		controller.update(cmd)
		assert '/loadBalancer/show?name=helloworld-unittest-frontend' == response.redirectUrl
		assert flash.message.startsWith("Load Balancer 'helloworld-unittest-frontend' has been created.")
	}
		
}
