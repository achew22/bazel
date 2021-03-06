// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.rules.objc;

import static com.google.devtools.build.lib.rules.objc.ObjcProvider.NESTED_BUNDLE;
import static com.google.devtools.build.lib.rules.objc.XcodeProductType.BUNDLE;

import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.rules.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.rules.objc.ObjcCommon.ResourceAttributes;

/**
 * Implementation for {@code objc_bundle_library}.
 */
public class ObjcBundleLibrary implements RuleConfiguredTargetFactory {

  @Override
  public ConfiguredTarget create(RuleContext ruleContext) throws InterruptedException {
    ObjcCommon common = common(ruleContext);
    Bundling bundling = bundling(ruleContext, common);

    XcodeProvider.Builder xcodeProviderBuilder = new XcodeProvider.Builder();
    NestedSetBuilder<Artifact> filesToBuild = NestedSetBuilder.stableOrder();

    new ResourceSupport(ruleContext)
        .validateAttributes()
        .addXcodeSettings(xcodeProviderBuilder);

    if (ruleContext.hasErrors()) {
      return null;
    }

    new BundleSupport(ruleContext, bundling)
        .registerActions(common.getObjcProvider())
        .validate(common.getObjcProvider())
        .addXcodeSettings(xcodeProviderBuilder);

    if (ruleContext.hasErrors()) {
      return null;
    }

    new XcodeSupport(ruleContext)
        .addFilesToBuild(filesToBuild)
        .addXcodeSettings(xcodeProviderBuilder, common.getObjcProvider(), BUNDLE)
        .addDependencies(xcodeProviderBuilder, new Attribute("bundles", Mode.TARGET))
        .registerActions(xcodeProviderBuilder.build());

    ObjcProvider nestedBundleProvider = new ObjcProvider.Builder()
        .add(NESTED_BUNDLE, bundling)
        .build();

    return ObjcRuleClasses.ruleConfiguredTarget(ruleContext, filesToBuild.build())
        .addProvider(XcodeProvider.class, xcodeProviderBuilder.build())
        .addProvider(ObjcProvider.class, nestedBundleProvider)
        .build();
  }

  private Bundling bundling(RuleContext ruleContext, ObjcCommon common) {
    IntermediateArtifacts intermediateArtifacts =
        ObjcRuleClasses.intermediateArtifacts(ruleContext);
    ObjcConfiguration objcConfiguration = ObjcRuleClasses.objcConfiguration(ruleContext);
    return new Bundling.Builder()
        .setName(ruleContext.getLabel().getName())
        .setArchitecture(objcConfiguration.getIosCpu())
        .setBundleDirFormat("%s.bundle")
        .setObjcProvider(common.getObjcProvider())
        .addInfoplistInputFromRule(ruleContext)
        .setIntermediateArtifacts(intermediateArtifacts)
        .setMinimumOsVersion(objcConfiguration.getMinimumOs())
        .build();
  }

  private ObjcCommon common(RuleContext ruleContext) {
    return new ObjcCommon.Builder(ruleContext)
        .setResourceAttributes(new ResourceAttributes(ruleContext))
        .addDepObjcProviders(
            ruleContext.getPrerequisites("bundles", Mode.TARGET, ObjcProvider.class))
        .setIntermediateArtifacts(ObjcRuleClasses.intermediateArtifacts(ruleContext))
        .build();
  }
}
