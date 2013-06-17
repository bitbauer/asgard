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

import com.amazonaws.AmazonServiceException
import com.amazonaws.services.autoscaling.model.AutoScalingGroup
import com.amazonaws.services.autoscaling.model.LaunchConfiguration
import com.netflix.asgard.model.AutoScalingGroupData
import com.netflix.asgard.model.JanitorMode
import com.netflix.asgard.model.InstancePriceType
import com.netflix.asgard.model.Subnets
import com.amazonaws.services.ec2.model.SecurityGroup
import com.netflix.grails.contextParam.ContextParam
import grails.converters.JSON
import grails.converters.XML
import javax.xml.bind.DatatypeConverter
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormatter
import org.joda.time.format.ISODateTimeFormat

@ContextParam('region')
class LaunchConfigurationController {

    def applicationService
    def awsAutoScalingService
    def awsEc2Service
    def flagService
    def instanceTypeService
	def spotInstanceRequestService
	def taskService
		
    def static allowedMethods = [delete:'POST', save:'POST', update:'POST', cleanup: 'POST', massDelete: 'POST']

    def index = { redirect(action: 'list', params:params) }

    def list = {
        UserContext userContext = UserContext.of(request)
        Set<String> appNames = Requests.ensureList(params.id).collect { it.split(',') }.flatten() as Set<String>
        Collection<LaunchConfiguration> launchConfigs = awsAutoScalingService.getLaunchConfigurations(userContext)
        if (appNames) {
            launchConfigs = launchConfigs.findAll { LaunchConfiguration launchConfig ->
                appNames.contains(Relationships.appNameFromLaunchConfigName(launchConfig.launchConfigurationName))
            }
        }
        launchConfigs = launchConfigs.sort { it.launchConfigurationName.toLowerCase() }
        withFormat {
            html {
                [
                    'launchConfigurations': launchConfigs,
                    'appNames': appNames
                ]
            }
            xml { new XML(launchConfigs).render(response) }
            json { new JSON(launchConfigs).render(response) }
        }
    }

    def terse = {
        UserContext userContext = UserContext.of(request)
        List<String> columns = Requests.ensureList(params.columns).collect { it.split(',') }.flatten().sort()
        columns = columns ?: ['launchConfigurationName', 'imageId']
        Collection<LaunchConfiguration> configurations = awsAutoScalingService.getLaunchConfigurations(userContext)
        def launchConfigAttributes = configurations.collect { launchConfig ->
            columns.inject([:], { attrs, column -> attrs[column] = launchConfig[column]; attrs } )
        }
        launchConfigAttributes = launchConfigAttributes.unique().sort { it.imageId }
        withFormat {
            xml {
                render() {
                    launchConfigurations {
                        launchConfigAttributes.each { launchConfiguration(it) }
                    }
                }
            }
        }
    }

    def show = {
        UserContext userContext = UserContext.of(request)
        String name = params.name ?: params.id
        LaunchConfiguration lc = awsAutoScalingService.getLaunchConfiguration(userContext, name)
        if (!lc) {
            Requests.renderNotFound('Launch Configuration', name, this)
        } else {
            String appName = Relationships.appNameFromLaunchConfigName(name)
            AutoScalingGroup group = awsAutoScalingService.getAutoScalingGroupForLaunchConfig(userContext, name)
            String clusterName = Relationships.clusterFromGroupName(group?.autoScalingGroupName)
            def details = [
                    lc: lc,
                    image: awsEc2Service.getImage(userContext, lc.imageId),
                    app: applicationService.getRegisteredApplication(userContext, appName),
                    group: group,
                    cluster: clusterName
            ]
            withFormat {
                html { return details }
                xml { new XML(details).render(response) }
                json { new JSON(details).render(response) }
            }
        }
    }

	def edit = {
		UserContext userContext = UserContext.of(request)
		String name = params.name ?: params.id
        LaunchConfiguration lc = awsAutoScalingService.getLaunchConfiguration(userContext, name)
		if (!lc) {
			Requests.renderNotFound('Launch Configuration', name, this)
			return
		}
		Subnets subnets = awsEc2Service.getSubnets(userContext)
		Map<String, String> purposeToVpcId = subnets.mapPurposeToVpcId()
        String subnetPurpose = params.subnetPurpose ?: null
		String vpcId = purposeToVpcId[subnetPurpose]
        String appName = Relationships.appNameFromLaunchConfigName(name)
        AutoScalingGroup group = awsAutoScalingService.getAutoScalingGroupForLaunchConfig(userContext, name)
        String clusterName = Relationships.clusterFromGroupName(group?.autoScalingGroupName)
		String recommendSpotPrice = spotInstanceRequestService.recommendSpotPrice(userContext, params.instanceType)
		List<SecurityGroup> effectiveGroups = awsEc2Service.getEffectiveSecurityGroups(userContext).sort {
			it.groupName?.toLowerCase()
		}

		return [
			lc: lc,
            image: awsEc2Service.getImage(userContext, lc.imageId),
            app: applicationService.getRegisteredApplication(userContext, appName),
            group: group,
            cluster: clusterName,
			images: awsEc2Service.getAccountImages(userContext).sort { it.imageLocation.toLowerCase() },
			instanceTypes: instanceTypeService.getInstanceTypes(userContext),
			pricing: params.spotPrice == recommendSpotPrice ? InstancePriceType.SPOT.name() : InstancePriceType.ON_DEMAND.name(),
			vpcId: vpcId,
			securityGroupsGroupedByVpcId: effectiveGroups.groupBy { it.vpcId },
			selectedSecurityGroups: Requests.ensureList(params.securityGroups),
			keys: awsEc2Service.getKeys(userContext).sort { it.keyName.toLowerCase() },
			defKey: awsEc2Service.defaultKeyName
		]
	}

    def update = {

		final String oldLaunchConfigName = params.name ?: params.id
		UserContext userContext = UserContext.of(request)
		AutoScalingGroup asg = awsAutoScalingService.getAutoScalingGroupForLaunchConfig(userContext, oldLaunchConfigName)
		final String autoScalingGroupName = asg.autoScalingGroupName
		final String newLaunchConfigName = Relationships.buildLaunchConfigurationName(autoScalingGroupName)	
		//Launch Configuration
		String imageId = params.imageId
		String keyName = params.keyName
		List<String> securityGroups = Requests.ensureList(params.selectedSecurityGroups)
		String instanceType = params.instanceType
		String encodedUserData = DatatypeConverter.printBase64Binary(params.userData.replaceAll("\r\n", "\n").getBytes())
		String kernelId = params.kernelId ?: null
		String ramdiskId = params.ramdiskId ?: null
		String iamInstanceProfile = params.iamInstanceProfile ?: configService.defaultIamRole
		String msg = "Create Launch Configuration: '${newLaunchConfigName}'."
		String error = ""
		String spotPrice = null
		boolean ebsOptimized = params.ebsOptimized ? params.ebsOptimized.toBoolean() : false

		if (params.pricing == InstancePriceType.SPOT.name()) {
			spotPrice = spotInstanceRequestService.recommendSpotPrice(userContext, instanceType)
		}
		
		taskService.runTask(userContext, msg, { Task task ->
			try {
				awsAutoScalingService.createLaunchConfiguration(
					userContext, newLaunchConfigName,
					imageId, keyName, securityGroups,
					encodedUserData, instanceType, kernelId,
					ramdiskId, iamInstanceProfile, spotPrice,
					ebsOptimized, task)
			} catch (AmazonServiceException e) {
				error = "Could not create new Launch Configuration: ${e} ${encodedUserData}"
			}
		}, Link.to(EntityType.launchConfiguration, newLaunchConfigName), null)
		

		if (awsAutoScalingService.getLaunchConfiguration(userContext, newLaunchConfigName) == null)	{
			Set<String> appNames = Requests.ensureList(params.id).collect { it.split(',') }.flatten() as Set<String>
			flash.message = error
			redirect(action: 'list', appNames: appNames)
    	} else {
		
			try {	
				final AutoScalingGroupData autoScalingGroupData = AutoScalingGroupData.forUpdate(	
					autoScalingGroupName,	
					newLaunchConfigName,	
					asg.minSize,	
					asg.desiredCapacity,	
					asg.maxSize,	
					asg.defaultCooldown,	
					asg.healthCheckType,	
					asg.healthCheckGracePeriod,	
					asg.terminationPolicies,	
					asg.availabilityZones)	
				asg = awsAutoScalingService.updateAutoScalingGroup(userContext, autoScalingGroupData)	
				awsAutoScalingService.deleteLaunchConfiguration(userContext, oldLaunchConfigName)
				flash.message = "Launch Config '${oldLaunchConfigName}' has been replaced by '${newLaunchConfigName}'. "
				redirect(action: 'show', name: newLaunchConfigName, params: [id: newLaunchConfigName])
			} catch (AmazonServiceException e) {	
				if (awsAutoScalingService.getAutoScalingGroupForLaunchConfig(userContext, newLaunchConfigName) == null) {	
					awsAutoScalingService.deleteLaunchConfiguration(userContext, newLaunchConfigName)	
				}
				flash.message = "Could not delete old Launch Configuration: ${e}"
				redirect(action: 'list', appNames: appNames)
			}
		}
	}
	
    def delete = {
        UserContext userContext = UserContext.of(request)
        def name = params.name
        def matchingGroup = awsAutoScalingService.getAutoScalingGroups(userContext).find {
            it.launchConfigurationName == name
        }
        if (matchingGroup) {
            flash.message = "Could not delete Launch Configuration $name: " +
                    "in use by Auto Scaling Group ${matchingGroup.autoScalingGroupName}"
        } else {
            try {
                awsAutoScalingService.deleteLaunchConfiguration(userContext, name)
                flash.message = "Launch Configuration '${name}' has been deleted."
            } catch (Exception e) {
                flash.message = "Could not delete Launch Configuration: ${e}"
            }
        }
        redirect(action: 'list')
    }

    def massDelete = {
        UserContext userContext = UserContext.of(request)
        Integer daysAgo = params.daysAgo as Integer
        String message = doMassDelete(userContext, daysAgo)
        render "<pre>${message}</pre>"
    }

    // This is the old clean up endpoint. After a release, the Jenkins job can be changed to point to massDelete. Then
    // this method can be deleted.
    @Deprecated
    def cleanup = {
        UserContext userContext = UserContext.of(request)
        Integer daysAgo = params.daysAgo as Integer
        String message = doMassDelete(userContext, daysAgo)
        render "<pre>${message}</pre>"
    }

    private String doMassDelete(UserContext userContext, int daysAgo) {
        Check.atLeast(1, daysAgo, 'daysAgo')
        DateTime cutOffDate = new DateTime().minusDays(daysAgo)
        boolean deleteUnreferenced = params.deleteUnreferenced ? Boolean.valueOf(params.deleteUnreferenced) : false
        JanitorMode mode = params.mode ? JanitorMode.valueOf(params.mode) : JanitorMode.EXECUTE
        Collection<AutoScalingGroup> allGroups = awsAutoScalingService.getAutoScalingGroups(userContext)
        Collection<LaunchConfiguration> allConfigs = awsAutoScalingService.getLaunchConfigurations(userContext)
        Collection<LaunchConfiguration> oldUnusedConfigs = allConfigs.findAll { LaunchConfiguration lc ->
            Boolean configIsOld = new DateTime(lc.createdTime.time).isBefore(cutOffDate)
            Boolean configNotInUse = !(allGroups.any { it.launchConfigurationName == lc.launchConfigurationName })
            (configIsOld && configNotInUse) || (deleteUnreferenced && !autoscalingGroupExists(userContext, lc))
        }

        DateTimeFormatter formatter = ISODateTimeFormat.date()

        String executeMessage = "Deleting ${oldUnusedConfigs.size()} unused launch configs from before" +
                " ${formatter.print(cutOffDate)} \n"
        String dryRunMessage = "Dry run mode. If executed, this job would delete ${oldUnusedConfigs.size()} unused" +
                "launch configs from before ${formatter.print(cutOffDate)} \n"
        String message = JanitorMode.EXECUTE == mode ? executeMessage : dryRunMessage
        oldUnusedConfigs.sort { it.createdTime }
        oldUnusedConfigs.each { LaunchConfiguration lc ->
            try {
                if (mode == JanitorMode.EXECUTE) {
                    awsAutoScalingService.deleteLaunchConfiguration(userContext, lc.launchConfigurationName)
                }
                message += "Deleted ${formatter.print(lc.createdTime.time)} ${lc.launchConfigurationName} \n"
            } catch (Exception e) {
                message += "Could not delete Launch Configuration ${lc.launchConfigurationName}: ${e} \n"
            }
        }
        return message
    }

    private boolean autoscalingGroupExists(UserContext userContext, LaunchConfiguration lc) {
        awsAutoScalingService.getAutoScalingGroupForLaunchConfig(userContext, lc.launchConfigurationName)
    }

}
