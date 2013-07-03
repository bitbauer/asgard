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
import grails.test.MockUtils
import grails.test.mixin.TestFor
import spock.lang.Specification

@TestFor(LaunchConfigurationController)
class LaunchConfigurationControllerSpec extends Specification {

    void setup() {
		Mocks.createDynamicMethods()
		TestUtils.setUpMockRequest()
		controller.awsAutoScalingService = Mocks.awsAutoScalingService()
		controller.applicationService = Mocks.applicationService()
		controller.awsEc2Service = Mocks.awsEc2Service()
    }

    def 'show should display launchconfiguration'() {
        final mockAwsCloudWatchService = Mock(AwsCloudWatchService)
        controller.awsCloudWatchService = mockAwsCloudWatchService
        mockAwsCloudWatchService.getAlarm(_, 'alarm-1') >> {
            new MetricAlarm(comparisonOperator: 'GreaterThanThreshold', statistic: 'Average', alarmName: 'alarm-1')
        }

        controller.params.id = 'alarm-1'

        when:
        final attrs = controller.show()

        then:
        'alarm-1' == attrs.alarm.alarmName
    }

    def 'show should not display nonexistent launchconfiguration'() {
        controller.awsCloudWatchService = Mocks.awsCloudWatchService()
        controller.params.id = 'doesntexist'

        when:
        controller.show()

        then:
        '/error/missing' == view
        "Alarm 'doesntexist' not found in us-east-1 test" == controller.flash.message
    }

    def 'update should modify alarm dimensions'() {
        controller.awsCloudWatchService = Mock(AwsCloudWatchService) {
            1 * getAlarm(_, 'alarm-1') >> new MetricAlarm(namespace: 'AWS/SQS',
                    metricName: 'stuff')
            1 * updateAlarm(_, new AlarmData(alarmName: 'alarm-1', description: '',
                    comparisonOperator: AlarmData.ComparisonOperator.GreaterThanThreshold,
                    metricName: 'importantSomethingsPerWhatever', namespace: 'AWS/SQS',
                    statistic: AlarmData.Statistic.Average, period: 700, evaluationPeriods: 5, threshold: 80,
                    actionArns: ['arn:aws:sns:blah'], policyNames: [], topicNames: ['blah'],
                    dimensions: [QueueName: 'fillItUp']))
            1 * getDimensionsForNamespace('AWS/SQS') >> ['QueueName']
        }
        controller.awsSnsService = Mock(AwsSnsService)

        final createCommand = new AlarmValidationCommand()
        createCommand.with {
            alarmName = 'alarm-1'
            comparisonOperator = 'GreaterThanThreshold'
            metric = 'importantSomethingsPerWhatever'
            namespace = 'AWS/SQS'
            statistic = 'Average'
            period = 700
            evaluationPeriods = 5
            threshold = 80
            topic = 'api-prodConformity-Report'
            policy = 'nflx_newton_client-v003-17'
        }
        createCommand.validate()
        controller.params.with {
            QueueName = 'fillItUp'
            AutoScalingGroupName = 'asgName'
        }

        when:
        controller.update(createCommand)

        then:
        '/alarm/show/alarm-1' == response.redirectedUrl

        1 * controller.awsSnsService.getTopic(_, 'api-prodConformity-Report') >> {
            new TopicData('arn:aws:sns:blah')
        }

    }


}
