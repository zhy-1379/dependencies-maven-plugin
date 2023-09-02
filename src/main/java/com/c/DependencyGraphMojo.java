package com.c;

import cn.hutool.core.text.CharSequenceUtil;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyNode;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Set;

/**
 * Goal which touches a timestamp file.
 *
 * @phase process-sources
 */
@Mojo(name = "graph")
public class DependencyGraphMojo extends AbstractMojo {

    /**
     * The current Maven project.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * The current Maven session.
     */
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    /**
     * The dependency graph builder to use.
     */
    @Component
    private DependencyGraphBuilder dependencyGraphBuilder;

    @Parameter
    private String url;

    @Parameter
    private String username;

    @Parameter
    private String password;

    @SuppressWarnings("all")
    public void execute() {
        getLog().info("==================== MyMojo Start =================");

        try {
            getLog().info("project: " + project);
            getLog().info("=== project.getArtifacts() ===");
            Set<Artifact> artifacts = project.getArtifacts();
            if (artifacts == null || artifacts.isEmpty()) {
                Field resolvedArtifacts = MavenProject.class.getDeclaredField("resolvedArtifacts");
                resolvedArtifacts.setAccessible(true);
                artifacts = (Set<Artifact>) resolvedArtifacts.get(project);
            }

            Artifact projectArtifact = project.getArtifact();
            // Connection con = DriverManager.getConnection("jdbc:neo4j:bolt://localhost:7687/", "neo4j", "");
            Connection con = DriverManager.getConnection(url, username, password);
            long projectArtifactNodeId = getNodeId(con, projectArtifact);
            if (projectArtifactNodeId <= 0) {
                getLog().warn("节点不存在或创建失败：" + projectArtifact.toString());
            } else {
                if (artifacts != null && !artifacts.isEmpty()) {
                    for (Artifact artifact : artifacts) {
                        getLog().info(artifact.toString());
                        long artifactNodeId = getNodeId(con, artifact);
                        if (artifactNodeId <= 0) {
                            getLog().error("节点不存在或创建失败：" + artifact.toString());
                            continue;
                        }

                        tryCreateEdge(con, projectArtifact, projectArtifactNodeId, artifact, artifactNodeId);
                    }
                }
            }
            con.close();

            getLog().info("===  dependencyGraphBuilder: " + dependencyGraphBuilder + " === ");

            session.getProjectBuildingRequest().setProject(project);
            ProjectDependencyGraph projectDependencyGraph = session.getProjectDependencyGraph();
            DependencyNode node = dependencyGraphBuilder.buildDependencyGraph(session.getProjectBuildingRequest(), null);
            resolveDepTree(node, 0);
        } catch (Throwable e) {
            getLog().error(e);
        }
    }

    private void tryCreateEdge(Connection con, Artifact projectArtifact, long projectArtifactNodeId, Artifact artifact, long artifactNodeId) {
        String existSql = CharSequenceUtil.format("" +
                        "MATCH (f)-[r:dependency{scope:\"{}\"}]->(t) " +
                        "WHERE id(f) = {} and id(t) = {} " +
                        "RETURN id(r);",
                artifact.getScope(), projectArtifactNodeId, artifactNodeId);
        try (Statement stmt = con.createStatement()) {
            ResultSet resultSet = stmt.executeQuery(existSql);
            if (resultSet.next()) {
                getLog().info("依赖关系已经存在：(" + projectArtifact.getArtifactId() + ")-[dep]->(" + artifact.getArtifactId() + ")");
                return;
            }
        } catch (Exception e) {
            getLog().error(existSql);
            getLog().error(e);
        }

        String createDepSql = CharSequenceUtil.format("" +
                        "MATCH (f),(t) " +
                        "WHERE id(f) = {} and id(t) = {} " +
                        "CREATE (f)-[r:dependency{scope:\"{}\"}]->(t) " +
                        "RETURN id(r);",
                projectArtifactNodeId, artifactNodeId, artifact.getScope());
        try (Statement stmt = con.createStatement()) {
            ResultSet resultSet = stmt.executeQuery(createDepSql);
            if (resultSet.next()) {
                getLog().info("创建依赖关系成功：(" + projectArtifact.getArtifactId() + ")-[dep]->(" + artifact.getArtifactId() + ")");
            } else {
                getLog().error("创建依赖关系失败：(" + projectArtifact.getArtifactId() + ")-[dep]->(" + artifact.getArtifactId() + ")");
            }
        } catch (Exception e) {
            getLog().error(createDepSql);
            getLog().error(e);
        }
    }

    private long getNodeId(Connection con, Artifact artifact) {
        String existsSql = CharSequenceUtil.format(
                "MATCH (n:jar{group_id:\"{}\", artifact_id:\"{}\", version:\"{}\"}) RETURN id(n);",
                artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());

        try (Statement stmt = con.createStatement()) {
            ResultSet resultSet = stmt.executeQuery(existsSql);
            if (resultSet.next()) {
                return resultSet.getLong("id(n)");
            }
        } catch (Exception e) {
            getLog().error(existsSql);
            getLog().error(e);
        }
        String createSql = CharSequenceUtil.format(
                "CREATE (n:jar{group_id:\"{}\", artifact_id:\"{}\", version:\"{}\"}) RETURN id(n);",
                artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion());

        try (Statement stmt = con.createStatement()) {
            ResultSet resultSet = stmt.executeQuery(createSql);
            if (resultSet.next()) {
                return resultSet.getLong("id(n)");
            }
        } catch (Exception e) {
            getLog().error(createSql);
            getLog().error(e);
        }

        return -1;
    }

    public void resolveDepTree(DependencyNode child, int level) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < level; i++) {
            s.append("-");
        }
        getLog().info(s + child.getArtifact().toString());
        if (child.getChildren() != null) {
            for (DependencyNode childChild : child.getChildren()) {
                resolveDepTree(childChild, level + 1);
            }
        }
    }

}
