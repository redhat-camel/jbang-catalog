package com.redhat.camel.cli.test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import io.restassured.path.xml.XmlPath;
import io.restassured.path.xml.element.NodeChildren;

public class UpdateVersionsTest {
    private static final Logger log = LoggerFactory.getLogger(GitHubTokenCredentials.class);

    private static final Pattern BRANCH_PATTERN = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+($|[^0-9])");
    private static final Pattern JAVA_OPTIONS_PATTERN = Pattern.compile("\n//[ \t]*JAVA_OPTIONS[ \t]+(.*)(\r?\n)");

    @Test
    void update() throws IOException, InvalidRemoteException, TransportException, GitAPIException, URISyntaxException {

        /* Input parameters */
        String remoteUrl = System.getenv("JBANG_CATALOG_GIT_REPOSITORY");
        if (remoteUrl == null || remoteUrl.isEmpty()) {
            remoteUrl = "https://github.com/redhat-camel/jbang-catalog.git";
        }
        log.info("Using remote " + remoteUrl);
        final String ghRepository = System.getenv("GITHUB_REPOSITORY");
        final String ghToken = System.getenv("GITHUB_TOKEN");
        final String issueId = System.getenv("GITHUB_ISSUE_ID");
        try {

            final String remoteMavenRepositoryBaseUrl = "https://maven.repository.redhat.com/ga";
            final String quarkusRegistryBaseUrl = "https://registry.quarkus.redhat.com";
            final ComparableVersion minimalCamelVersion = new ComparableVersion("4.14.0");

            final String remoteAlias = "upstream";
            final CredentialsProvider creds = new GitHubTokenCredentials(ghToken);

            /* From branch name such as 4.14.x to RHBQ Platform version, such as 3.27.0.redhat-00001 */
            final Map<String, String> camelMajorMinorToRhbqPlatformVersion = collectVersions(
                    quarkusRegistryBaseUrl,
                    remoteMavenRepositoryBaseUrl,
                    minimalCamelVersion);

            final String uuid = UUID.randomUUID().toString();
            final Path checkoutDir = Path.of("target/checkout-" + uuid);
            Files.createDirectories(checkoutDir);
            try (Git git = Git.init()
                    .setDirectory(checkoutDir.toFile())
                    .call()) {
                git.remoteAdd().setName(remoteAlias).setUri(new URIish(remoteUrl)).call();

                /* remoteBranchMap is from branch name such as 4.14.x to commit hash so that we can properly reset it */
                /*
                 * remoteBranchMap is ordered from newest to oldest branch so that we can pick the first as a base for a
                 * new major.minor.x branch
                 */
                final Map<String, String> remoteBranchMap = fetchBranches(git, remoteUrl, remoteAlias, creds);

                final Path camelJBangJavaPath = checkoutDir.resolve("CamelJBang.java");
                for (Entry<String, String> en : camelMajorMinorToRhbqPlatformVersion.entrySet()) {
                    final String branch = en.getKey();
                    final String platformVersion = en.getValue();

                    final String remoteHead;
                    if (remoteBranchMap.containsKey(branch)) {
                        /* The branch exists in the remote already */
                        remoteHead = remoteBranchMap.get(branch);
                        log.info("Updating branch " + branch + " to RHBQ Platform " + platformVersion);
                    } else {
                        /*
                         * The branch does not exist yet in the remote so we create it based on the latest existing
                         * major.minor.x branch
                         */
                        final String latestExistingBranch = remoteBranchMap.keySet().iterator().next();
                        log.info("Creating branch " + branch + " from  " + latestExistingBranch
                                + " and updating it to RHBQ Platform " + platformVersion);
                        remoteHead = remoteBranchMap.get(latestExistingBranch);
                    }

                    /* Checkout or create the local branch */
                    git.branchCreate().setName(branch).setForce(true).setStartPoint(remoteHead).call();
                    git.checkout().setName(branch).call();
                    git.reset().setMode(ResetType.HARD).setRef(remoteHead).call();
                    final Ref ref = git.getRepository().exactRef("HEAD");
                    log.info("Reset the working copy to {}@{}", branch, ref.getObjectId().getName());

                    /* Check/edit the versions in CamelJBang.java */
                    final String oldSource = Files.readString(camelJBangJavaPath, StandardCharsets.UTF_8);
                    final Map<String, String> newProps = new LinkedHashMap<>();
                    newProps.put("-Dcamel.jbang.quarkusGroupId", "com.redhat.quarkus.platform");
                    newProps.put("-Dcamel.jbang.quarkusArtifactId", "quarkus-bom");
                    newProps.put("-Dcamel.jbang.quarkusVersion", platformVersion);
                    final String newSource = edit(oldSource, newProps);
                    if (!newSource.equals(oldSource)) {
                        Files.writeString(camelJBangJavaPath, newSource, StandardCharsets.UTF_8);
                        git.add().addFilepattern("CamelJBang.java").call();
                        final String msg = "Upgrade to RHBQ Platform " + platformVersion;
                        log.info("git: " + msg);
                        git.commit()
                                .setAuthor("Camel JBang Catalog Autoupdater", "autoupdater@localhost")
                                .setMessage(msg)
                                .call();
                        git.push()
                                .setRemote(remoteAlias)
                                .add(branch)
                                .setCredentialsProvider(creds)
                                .call();
                    } else {
                        log.info("No change in CamelJBang.java in branch " + branch);
                    }
                }
            }
            /* Close if needed */
            RestAssured.given()
                    .accept("application/vnd.github+json")
                    .header("Authorization", "Bearer " + ghToken)
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .body("""
                            {
                                "state":"closed"
                            }
                            """)
                    .patch("https://api.github.com/repos/" + ghRepository + "/issues/" + issueId)
                    .then()
                    .statusCode(200);
        } catch (Exception e) {
            reportFailure(e, ghRepository, issueId, ghToken);
        }
    }

    static void reportFailure(Exception e, String ghRepository, String issueId, String ghToken) {

        final Writer stackTrace = new StringWriter();
        try (PrintWriter pw = new PrintWriter(stackTrace)) {
            e.printStackTrace(pw);
        }

        /* Add comment */
        String st = stackTrace.toString()
        .replace("\"", "\\\"")
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("\t", "\\t");
        if (st.length() > 65000) {
            st = st.substring(0, 65000);
        }
        final String body = """
                {
                    "body" : "`update-versions` failed:\\n\\n```\\n%s\\n```"
                }
                """.formatted(st);
        //log.info("Creating new comment " + body);
        RestAssured.given()
                .accept("application/vnd.github+json")
                .header("Authorization", "Bearer " + ghToken)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .body(body)
                .post("https://api.github.com/repos/" + ghRepository + "/issues/" + issueId + "/comments")
                .then()
                .statusCode(201);

        /* Open the issue if needed */
        RestAssured.given()
                .accept("application/vnd.github+json")
                .header("Authorization", "Bearer " + ghToken)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .body("""
                        {
                            "state":"open"
                        }
                        """)
                .patch("https://api.github.com/repos/" + ghRepository + "/issues/" + issueId)
                .then()
                .statusCode(200);

    }

    static Map<String, String> fetchBranches(Git git, String remoteUrl, String remoteAlias, CredentialsProvider creds)
            throws InvalidRemoteException, TransportException, GitAPIException {
        final Set<String> remoteBranches = Git.lsRemoteRepository()
                .setCredentialsProvider(creds)
                .setHeads(true)
                .setTags(false)
                .setRemote(remoteUrl)
                .call().stream()
                .map(ref -> ref.getName().substring("refs/heads/".length()))
                .filter(b -> BRANCH_PATTERN.matcher(b).matches())
                .collect(Collectors.toCollection(() -> new TreeSet<>(new BranchComparator().reversed())));
        log.info("Available branches in {}: {}", remoteAlias, remoteBranches);

        Map<String, String> result = new LinkedHashMap<>();
        for (String branch : remoteBranches) {
            log.info("Fetching {} from {}", branch, remoteAlias);
            final String remoteRef = "refs/heads/" + branch;
            final FetchResult fetchResult = git.fetch()
                    .setRemote(remoteAlias)
                    .setRefSpecs(remoteRef)
                    .setCredentialsProvider(creds)
                    .call();
            final String sha1 = fetchResult.getAdvertisedRef(remoteRef).getObjectId().getName();
            result.put(branch, sha1);
        }
        return Collections.unmodifiableMap(result);
    }

    static Map<String, String> collectVersions(String quarkusRegistryBaseUrl, String remoteMavenRepositoryBaseUrl,
            ComparableVersion minimalCamelVersion) {
        Map<String, String> result = new LinkedHashMap<>();

        final JsonPath jsonPath = RestAssured.get(quarkusRegistryBaseUrl + "/client/platforms")
                .then()
                .statusCode(200)
                .extract().jsonPath();

        final List<Map<String, Object>> plfs = jsonPath.get("platforms");

        for (Map<String, Object> plf : plfs) {
            if ("com.redhat.quarkus.platform".equals(plf.get("platform-key"))) {
                final List<Map<String, Object>> streams = (List<Map<String, Object>>) plf.get("streams");
                for (Map<String, Object> stream : streams) {
                    final List<Map<String, Object>> releases = (List<Map<String, Object>>) stream.get("releases");
                    for (Map<String, Object> release : releases) {

                        final List<String> boms = (List<String>) release.get("member-boms");
                        boms.stream()
                                .filter(gav -> gav.startsWith("com.redhat.quarkus.platform:quarkus-camel-bom:"))
                                .findFirst()
                                .ifPresent(ceqBomGav -> {
                                    final String[] ceqBomGavSegments = ceqBomGav.split(":");
                                    final String bomVersion = ceqBomGavSegments[4];
                                    final String url = toUrl(remoteMavenRepositoryBaseUrl, ceqBomGavSegments[0],
                                            ceqBomGavSegments[1], bomVersion, "pom");
                                    final XmlPath xmlPath = RestAssured.get(url)
                                            .then()
                                            .statusCode(200)
                                            .extract().xmlPath();

                                    final NodeChildren deps = xmlPath
                                            .get("project.dependencyManagement.dependencies.dependency");
                                    final Optional<String> camelVersionOpt = deps.list().stream()
                                            .map(n -> {
                                                String groupId = n.getNode("groupId").value();
                                                String artifactId = n.getNode("artifactId").value();
                                                if ("org.apache.camel".equals(groupId) && "camel-direct".equals(artifactId)) {
                                                    String version = n.getNode("version").value();
                                                    return version;
                                                }
                                                return null;
                                            })
                                            .filter(v -> v != null)
                                            .findFirst();
                                    if (camelVersionOpt.isEmpty()) {
                                        throw new IllegalStateException("org.apache.camel:camel-direct not found in " + url);
                                    }
                                    final String camelVersion = camelVersionOpt.get();
                                    final ComparableVersion comparableCamelVersion = new ComparableVersion(camelVersion);
                                    if (minimalCamelVersion.compareTo(comparableCamelVersion) > 0) {
                                        log.info("Skipping Camel version " + camelVersion + " in " + ceqBomGav);
                                        return;
                                    }

                                    log.info("Found Camel " + camelVersion + " in " + ceqBomGav);

                                    final String[] camelVersionSegments = camelVersion.split("\\.");
                                    final String camelMajorMinorBranch = camelVersionSegments[0] + "." + camelVersionSegments[1]
                                            + ".x";
                                    if (result.containsKey(camelMajorMinorBranch)) {
                                        throw new IllegalStateException(
                                                camelMajorMinorBranch + " available in more than one platform streams: "
                                                        + result.get(camelMajorMinorBranch)
                                                        + " and " + bomVersion);
                                    }
                                    result.put(camelMajorMinorBranch, bomVersion);
                                });
                    }
                }
            }
        }

        return Collections.unmodifiableMap(result);
    }

    static String edit(String oldSource, Map<String, String> props) {
        StringBuilder sb = new StringBuilder();
        Matcher m = JAVA_OPTIONS_PATTERN.matcher(oldSource);
        if (m.find()) {
            final String javaOpts = m.group(1);
            final Map<String, String> oldProps = new LinkedHashMap<>();
            for (String pair : javaOpts.split("[ \t]+")) {
                String[] kv = pair.split("=");
                oldProps.put(kv[0], kv.length == 1 ? null : kv[1]);
            }
            oldProps.putAll(props);
            StringBuilder replacementBuilder = new StringBuilder("\n//JAVA_OPTIONS ");

            oldProps.entrySet().stream()
                    .forEach(en -> {
                        if (replacementBuilder.charAt(replacementBuilder.length() - 1) != ' ') {
                            replacementBuilder.append(' ');
                        }
                        replacementBuilder.append(en.getKey()).append("=").append(en.getValue());
                    });
            replacementBuilder.append(m.group(2));

            final String replacement = replacementBuilder.toString();
            if (javaOpts.equals(replacement)) {
                return oldSource;
            }
            m.appendReplacement(sb, replacement);
        } else {
            throw new IllegalStateException("Could not find " + JAVA_OPTIONS_PATTERN.pattern() + " in CamelJBang.java");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static String toUrl(String url, String groupId, String artifactId, String version, String type) {
        final StringBuilder sb = new StringBuilder();
        sb.append(url);
        if (!url.endsWith("/")) {
            sb.append('/');
        }
        sb.append(groupId.replace('.', '/'))
                .append('/').append(artifactId)
                .append('/').append(version)
                .append('/').append(artifactId).append('-').append(version).append(".").append(type);
        return sb.toString();
    }

    static class GitHubTokenCredentials extends CredentialsProvider {

        private String ghToken;

        public GitHubTokenCredentials(String ghToken) {
            this.ghToken = ghToken;
        }

        @Override
        public boolean isInteractive() {
            return false;
        }

        @Override
        public boolean supports(CredentialItem... items) {
            for (CredentialItem i : items) {
                if (i instanceof CredentialItem.InformationalMessage) {
                    continue;
                }
                if (i instanceof CredentialItem.Username) {
                    continue;
                }
                if (i instanceof CredentialItem.Password) {
                    continue;
                }
                if (i instanceof CredentialItem.StringType) {
                    if (i.getPromptText().equals("Password: ")) {
                        continue;
                    }
                }
                if (i instanceof CredentialItem.YesNoType) {
                    if (i.getPromptText().startsWith("The authenticity of host 'github.com' can't be established.")) {
                        continue;
                    }
                }
                return false;
            }
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean get(URIish uri, CredentialItem... items) throws UnsupportedCredentialItem {
            String username = "x-access-token";
            for (CredentialItem i : items) {
                if (i instanceof CredentialItem.InformationalMessage) {
                    continue;
                }
                if (i instanceof CredentialItem.Username) {
                    ((CredentialItem.Username) i).setValue(username);
                    continue;
                }
                if (i instanceof CredentialItem.Password && ghToken != null) {
                    ((CredentialItem.Password) i).setValue(ghToken.toCharArray());
                    continue;
                }
                if (i instanceof CredentialItem.StringType && ghToken != null) {
                    if (i.getPromptText().equals("Password: ")) {
                        ((CredentialItem.StringType) i).setValue(ghToken);
                        continue;
                    }
                }
                if (i instanceof CredentialItem.YesNoType && ghToken != null) {
                    if (i.getPromptText().startsWith("The authenticity of host 'github.com' can't be established.")) {
                        ((CredentialItem.YesNoType) i).setValue(true);
                        continue;
                    }
                }
                throw new UnsupportedCredentialItem(uri, i.getClass().getName()
                        + ":" + i.getPromptText());
            }
            return true;
        }

    }

    static class BranchComparator implements Comparator<String> {

        @Override
        public int compare(String branch1, String branch2) {
            String v1 = branch1.replace(".x", ".9999");
            String v2 = branch2.replace(".x", ".9999");
            return new ComparableVersion(v1).compareTo(new ComparableVersion(v2));
        }

    }
}
