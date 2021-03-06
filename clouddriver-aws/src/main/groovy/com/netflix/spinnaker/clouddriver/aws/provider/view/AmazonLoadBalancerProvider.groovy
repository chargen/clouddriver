/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.aws.provider.view

import com.fasterxml.jackson.annotation.JsonProperty
import com.netflix.spinnaker.cats.cache.Cache
import com.netflix.spinnaker.cats.cache.CacheData
import com.netflix.spinnaker.cats.cache.CacheFilter
import com.netflix.spinnaker.cats.cache.RelationshipCacheFilter
import com.netflix.spinnaker.clouddriver.aws.AmazonCloudProvider
import com.netflix.spinnaker.clouddriver.model.LoadBalancerInstance
import com.netflix.spinnaker.clouddriver.aws.data.Keys
import com.netflix.spinnaker.clouddriver.aws.model.AmazonInstance
import com.netflix.spinnaker.clouddriver.aws.model.AmazonLoadBalancer
import com.netflix.spinnaker.clouddriver.aws.model.AmazonServerGroup
import com.netflix.spinnaker.clouddriver.aws.provider.AwsProvider
import com.netflix.spinnaker.clouddriver.model.LoadBalancerProvider
import com.netflix.spinnaker.clouddriver.model.LoadBalancerServerGroup
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

import static com.netflix.spinnaker.clouddriver.core.provider.agent.Namespace.*

@Component
class AmazonLoadBalancerProvider implements LoadBalancerProvider<AmazonLoadBalancer> {

  final String cloudProvider = AmazonCloudProvider.ID

  private final Cache cacheView
  private final AwsProvider awsProvider

  @Autowired
  public AmazonLoadBalancerProvider(Cache cacheView, AwsProvider awsProvider) {
    this.cacheView = cacheView
    this.awsProvider = awsProvider
  }

  Collection<CacheData> resolveRelationshipData(CacheData source, String relationship) {
    source.relationships[relationship] ? cacheView.getAll(relationship, source.relationships[relationship]) : []
  }

  private Collection<CacheData> resolveRelationshipDataForCollection(Collection<CacheData> sources, String relationship, CacheFilter cacheFilter = null) {
    Set<String> relationships = sources.findResults { it.relationships[relationship]?: [] }.flatten()
    relationships ? cacheView.getAll(relationship, relationships, cacheFilter) : []
  }

  @Override
  Set<AmazonLoadBalancer> getApplicationLoadBalancers(String applicationName) {

    Map<String, AmazonServerGroup> serverGroups
    Set<String> keys = []

    CacheData application = cacheView.get(APPLICATIONS.ns, Keys.getApplicationKey(applicationName))

    def applicationServerGroups = application ? resolveRelationshipData(application, SERVER_GROUPS.ns) : []
    def allLoadBalancerKeys = cacheView.getIdentifiers(LOAD_BALANCERS.ns)

    applicationServerGroups.each { CacheData serverGroup ->
      Collection<String> serverGroupLoadBalancers = serverGroup.relationships[LOAD_BALANCERS.ns] ?: []
      serverGroupLoadBalancers.each {
          keys.add(it)
          def vpcKey = it + ':vpc-'
          keys.addAll(allLoadBalancerKeys.findAll { it.startsWith(vpcKey)})
      }
    }
    def nameMatches = allLoadBalancerKeys.findAll {
      it ==~ 'aws:.*:' + applicationName + '-.*' || it ==~ 'aws:.*:' + applicationName || it ==~ 'aws:.*:' + applicationName + ':.*'
    }

    keys.addAll(nameMatches)

    def allLoadBalancers = cacheView.getAll(LOAD_BALANCERS.ns, keys)

    def allInstances = resolveRelationshipDataForCollection(allLoadBalancers, INSTANCES.ns, RelationshipCacheFilter.none())
    def allServerGroups = resolveRelationshipDataForCollection(allLoadBalancers, SERVER_GROUPS.ns)

    Map<String, AmazonInstance> instances = translateInstances(allInstances)

    serverGroups = translateServerGroups(allServerGroups, instances)

    translateLoadBalancers(allLoadBalancers, serverGroups)
  }

  private static Set<AmazonLoadBalancer> translateLoadBalancers(Collection<CacheData> loadBalancerData, Map<String, AmazonServerGroup> serverGroups) {
    Set<AmazonLoadBalancer> loadBalancers = loadBalancerData.collect { loadBalancerEntry ->
      Map<String, String> loadBalancerKey = Keys.parse(loadBalancerEntry.id)
      AmazonLoadBalancer loadBalancer = new AmazonLoadBalancer(loadBalancerEntry.attributes)
      loadBalancer.name = loadBalancerKey.loadBalancer
      loadBalancer.region = loadBalancerKey.region
      loadBalancer.set("instances", loadBalancerEntry.attributes.instances.findResults { it.instanceId })
      loadBalancer.vpcId = loadBalancerKey.vpcId
      loadBalancer.account = loadBalancerKey.account
      def lbServerGroups = loadBalancerEntry.relationships[SERVER_GROUPS.ns]?.findResults { serverGroups.get(it) } ?: []
      lbServerGroups.each { serverGroup ->
        loadBalancer.serverGroups << new LoadBalancerServerGroup(
          name: serverGroup.name,
          isDisabled: serverGroup.isDisabled(),
          instances: serverGroup.instances ? serverGroup.instances.collect { instance ->
            def health = instance.health.find { it.loadBalancerName == loadBalancer.name } ?: [:]
            new LoadBalancerInstance(
              id: instance.name,
              zone: instance.zone,
              health:
                [
                  state: health.state,
                  reasonCode: health.reasonCode,
                  description: health.description
                ]
            )
          } : [],
          detachedInstances: serverGroup.any().detachedInstances
        )
      }
      loadBalancer
    }

    loadBalancers
  }

  private static Map<String, AmazonServerGroup> translateServerGroups(Collection<CacheData> serverGroupData, Map<String, AmazonInstance> instances) {
    Map<String, AmazonServerGroup> serverGroups = serverGroupData.collectEntries { serverGroupEntry ->
      Map<String, String> serverGroupKey = Keys.parse(serverGroupEntry.id)

      def serverGroup = new AmazonServerGroup(name: serverGroupKey.serverGroup, region: serverGroupKey.region)
      serverGroup.instances = serverGroupEntry.relationships[INSTANCES.ns]?.findResults { instances.get(it) }
      serverGroup.asg = serverGroupEntry.attributes.asg
      serverGroup.detachedInstances = serverGroupEntry.relationships[INSTANCES.ns]?.findResults { instances.get(it) ? null : Keys.parse(it).instanceId }
      [(serverGroupEntry.id) : serverGroup]
    }

    serverGroups
  }

  private Map<String, AmazonInstance> translateInstances(Collection<CacheData> instanceData) {
    Map<String, AmazonInstance> instances = instanceData.collectEntries { instanceEntry ->
      AmazonInstance instance = new AmazonInstance(instanceEntry.attributes)
      instance.name = instanceEntry.attributes.instanceId.toString()
      [(instanceEntry.id): instance]
    }
    addHealthToInstances(instanceData, instances)

    instances
  }

  private void addHealthToInstances(Collection<CacheData> instanceData, Map<String, AmazonInstance> instances) {
    // Health will only be picked up when the healthAgent's healthId contains 'load-balancer'
    Map<String, String> healthKeysToInstance = [:]
    def loadBalancingHealthAgents = awsProvider.healthAgents.findAll { it.healthId.contains('load-balancer')}

    instanceData.each { instanceEntry ->
      Map<String, String> instanceKey = Keys.parse(instanceEntry.id)
      loadBalancingHealthAgents.each {
        def key = Keys.getInstanceHealthKey(instanceKey.instanceId, instanceKey.account, instanceKey.region, it.healthId)
        healthKeysToInstance.put(key, instanceEntry.id)
      }
    }

    Collection<CacheData> healths = cacheView.getAll(HEALTH.ns, healthKeysToInstance.keySet(), RelationshipCacheFilter.none())
    healths.findAll { it.attributes.type == 'LoadBalancer' && it.attributes.loadBalancers }.each { healthEntry ->
      def instanceId = healthKeysToInstance.get(healthEntry.id)
      def interestingHealth = healthEntry.attributes.loadBalancers
      instances[instanceId].health.addAll(interestingHealth.collect {
        [
          loadBalancerName: it.loadBalancerName,
          state: it.state,
          reasonCode: it.reasonCode,
          description: it.description
        ]
      })
    }
  }

  List<AmazonLoadBalancerSummary> list() {
    def searchKey = Keys.getLoadBalancerKey('*', '*', '*', null, null) + '*'
    Collection<String> identifiers = cacheView.filterIdentifiers(LOAD_BALANCERS.ns, searchKey)
    getSummaryForLoadBalancers(identifiers).values() as List
  }

  AmazonLoadBalancerSummary get(String name) {
    def searchKey = Keys.getLoadBalancerKey(name, '*', '*', null, null)  + "*"
    Collection<String> identifiers = cacheView.filterIdentifiers(LOAD_BALANCERS.ns, searchKey).findAll {
      def key = Keys.parse(it)
      key.loadBalancer == name
    }
    getSummaryForLoadBalancers(identifiers).get(name)
  }

  // TODO: Remove, doesn't appear to be used.
  List<AmazonLoadBalancerSummary> getInAccountAndRegion(String account,
                                                        String region) {
    def searchKey = Keys.getLoadBalancerKey('*', account, region, null, null)
    Collection<String> identifiers = cacheView.filterIdentifiers(LOAD_BALANCERS.ns, searchKey)
    getSummaryForLoadBalancers(identifiers).values() as List
  }

  List<Map> byAccountAndRegionAndName(String account,
                                      String region,
                                      String name) {
    def searchKey = Keys.getLoadBalancerKey(name, account, region, null, null) + '*'
    Collection<String> identifiers = cacheView.filterIdentifiers(LOAD_BALANCERS.ns, searchKey).findAll {
      def key = Keys.parse(it)
      key.loadBalancer == name
    }

    cacheView.getAll(LOAD_BALANCERS.ns, identifiers).attributes
  }

  // TODO: Remove, doesn't appear to be used.
  Map getDetailsInAccountAndRegionByName(String account,
                                         String region,
                                         String name,
                                         String vpcId) {
    def key = Keys.getLoadBalancerKey(name, account, region, vpcId, null)
    cacheView.get(LOAD_BALANCERS.ns, key)?.attributes
  }

  private Map<String, AmazonLoadBalancerSummary> getSummaryForLoadBalancers(Collection<String> loadBalancerKeys) {
    Map<String, AmazonLoadBalancerSummary> map = [:]
    Map<String, CacheData> loadBalancers = cacheView.getAll(LOAD_BALANCERS.ns, loadBalancerKeys, RelationshipCacheFilter.none()).collectEntries { [(it.id): it] }
    for (lb in loadBalancerKeys) {
      CacheData loadBalancerFromCache = loadBalancers[lb]
      if (loadBalancerFromCache) {
        def parts = Keys.parse(lb)
        String name = parts.loadBalancer
        String region = parts.region
        String account = parts.account
        def summary = map.get(name)
        if (!summary) {
          summary = new AmazonLoadBalancerSummary(name: name)
          map.put name, summary
        }
        def loadBalancer = new AmazonLoadBalancerDetail()
        loadBalancer.account = parts.account
        loadBalancer.region = parts.region
        loadBalancer.name = parts.loadBalancer
        loadBalancer.vpcId = parts.vpcId
        loadBalancer.loadBalancerType = parts.loadBalancerType
        loadBalancer.securityGroups = loadBalancerFromCache.attributes.securityGroups

        summary.getOrCreateAccount(account).getOrCreateRegion(region).loadBalancers << loadBalancer
      }
    }
    map
  }

  // view models...

  static class AmazonLoadBalancerSummary implements LoadBalancerProvider.Item {
    private Map<String, AmazonLoadBalancerAccount> mappedAccounts = [:]
    String name

    AmazonLoadBalancerAccount getOrCreateAccount(String name) {
      if (!mappedAccounts.containsKey(name)) {
        mappedAccounts.put(name, new AmazonLoadBalancerAccount(name: name))
      }
      mappedAccounts[name]
    }

    @JsonProperty("accounts")
    List<AmazonLoadBalancerAccount> getByAccounts() {
      mappedAccounts.values() as List
    }
  }

  static class AmazonLoadBalancerAccount implements LoadBalancerProvider.ByAccount {
    private Map<String, AmazonLoadBalancerAccountRegion> mappedRegions = [:]
    String name

    AmazonLoadBalancerAccountRegion getOrCreateRegion(String name) {
      if (!mappedRegions.containsKey(name)) {
        mappedRegions.put(name, new AmazonLoadBalancerAccountRegion(name: name, loadBalancers: []))
      }
      mappedRegions[name]
    }

    @JsonProperty("regions")
    List<AmazonLoadBalancerAccountRegion> getByRegions() {
      mappedRegions.values() as List
    }
  }

  static class AmazonLoadBalancerAccountRegion implements LoadBalancerProvider.ByRegion {
    String name
    List<AmazonLoadBalancerSummary> loadBalancers
  }

  static class AmazonLoadBalancerDetail implements LoadBalancerProvider.Details {
    String account
    String region
    String name
    String vpcId
    String type = 'aws'
    String loadBalancerType
    List<String> securityGroups = []
  }
}
