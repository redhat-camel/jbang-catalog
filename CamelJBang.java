///usr/bin/env jbang "$0" "$@" ; exit $?

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//JAVA 17+
//REPOS central=https://repo1.maven.org/maven2,redhat.ga=https://maven.repository.redhat.com/ga/
//JAVA_OPTIONS -Dcamel.jbang.camelSpringBootVersion=4.18.3.redhat-00006 -Dcamel.jbang.quarkusGroupId=com.redhat.quarkus.platform -Dcamel.jbang.quarkusArtifactId=quarkus-bom -Dcamel.jbang.quarkusVersion=3.33.3.redhat-00001
//DEPS org.apache.camel:camel-bom:${camel.jbang.version:4.18.3.redhat-00005}@pom
//DEPS org.apache.camel:camel-jbang-core:${camel.jbang.version:4.18.3.redhat-00005}
//DEPS org.apache.camel.kamelets:camel-kamelets:${camel-kamelets.version:4.18.3.redhat-00004}

package main;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import org.apache.camel.dsl.jbang.core.commands.CamelJBangMain;

/**
 * Main to run CamelJBang
 */
public class CamelJBang {

    private static final String REDHAT_GA_REPO = "https://maven.repository.redhat.com/ga/";
    private static final Set<String> SIMPLE_COMMANDS = Set.of("export", "run", "init");
    private static final Map<String, Set<String>> SUB_COMMANDS = Map.of(
            "catalog", Set.of(),
            "update", Set.of(),
            "version", Set.of("list", "set"),
            "plugin", Set.of("add"),
            "dependency", Set.of("runtime"),
            "eval", Set.of("expression"),
            "transform", Set.of("message"));

    public static void main(String... args) {
        boolean hasRepos = Arrays.stream(args)
                .anyMatch(a -> a.startsWith("--repos=") || a.startsWith("--repo="));
        if (!hasRepos) {
            int insertPos = findReposInsertPosition(args);
            if (insertPos >= 0) {
                String[] newArgs = new String[args.length + 1];
                System.arraycopy(args, 0, newArgs, 0, insertPos);
                newArgs[insertPos] = "--repos=" + REDHAT_GA_REPO;
                System.arraycopy(args, insertPos, newArgs, insertPos + 1, args.length - insertPos);
                args = newArgs;
            }
        }
        CamelJBangMain.run(args);
    }

    private static int findReposInsertPosition(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (SIMPLE_COMMANDS.contains(args[i])) {
                return i + 1;
            }
            Set<String> subs = SUB_COMMANDS.get(args[i]);
            if (subs != null && i + 1 < args.length && !args[i + 1].startsWith("-")) {
                if (subs.isEmpty() || subs.contains(args[i + 1])) {
                    return i + 2;
                }
            }
        }
        return -1;
    }

}
