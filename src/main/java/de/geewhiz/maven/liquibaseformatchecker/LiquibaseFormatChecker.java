package de.geewhiz.maven.liquibaseformatchecker;

import static java.lang.String.format;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.maven.enforcer.rule.api.EnforcerRule;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.enforcer.rule.api.EnforcerRuleHelper;
import org.apache.maven.model.Resource;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.util.DirectoryScanner;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class LiquibaseFormatChecker implements EnforcerRule {
	private static final String ROOT_NODE = "databaseChangeLog";
    private static final String ATTRIBUTE_LOGICAL_FILE_PATH = "logicalFilePath";
    private static final String NODE_CHANGE_SET = "changeSet";
    private static final String ATTRIBUTE_CONTEXT = "context";

    private List<Resource> resourceFolders;
    private String[] includes;
    private String[] excludes;

    @Override
    public void execute(EnforcerRuleHelper helper) throws EnforcerRuleException {
        helper.getLog().info("Liquibase Format Checker is executed.");

        initialize(helper);

        boolean foundErrors = false;
        for (Resource resource : resourceFolders) {
            foundErrors |= foundErrorsInResourceFolder(helper, resource);
        }

        if (foundErrors) {
            throw new EnforcerRuleException("We found some liquibase errors. See previous log messages.");
        }
    }

    private boolean foundErrorsInResourceFolder(EnforcerRuleHelper helper, Resource resource) throws EnforcerRuleException {
        File resourceDirectory = new File(resource.getDirectory());
        if (!resourceDirectory.exists()) {
            helper.getLog().debug("== Resource folder [" + resourceDirectory.getAbsolutePath() + "] does not exist. Skipping.");
            return false;
        }

        helper.getLog().debug("== Scanning resource folder [" + resourceDirectory.getAbsolutePath() + "]");

        DirectoryScanner directoryScanner = new DirectoryScanner();
        directoryScanner.setBasedir(resourceDirectory);
        directoryScanner.setIncludes(includes);
        directoryScanner.setExcludes(excludes);

        directoryScanner.scan();

        helper.getLog().debug("== We do not scan folders: " + Arrays.toString(directoryScanner.getExcludedDirectories()));
        helper.getLog().debug("== We do not scan files: " + Arrays.toString(directoryScanner.getExcludedFiles()));

        boolean foundErrors = false;
        for (String relativeFilePath : directoryScanner.getIncludedFiles()) {
            File file = new File(resourceDirectory, relativeFilePath);
            if (!file.isFile()) {
                continue;
            }

            Element databaseChangelogElement = getDatabaseChangelogElement(file);
            if (databaseChangelogElement == null) {
                helper.getLog().debug("File [" + file.getAbsolutePath() + "] is not a databasechangelog file. Skipping.");
                continue;
            }
            
            helper.getLog().debug("Checking databasechangelog [" + file.getAbsolutePath() + "].");

            foundErrors |= foundLogicalFilePathErrors(helper, file, relativeFilePath, databaseChangelogElement);
            foundErrors |= foundMissingContextes(helper, file, databaseChangelogElement);
        }
        return foundErrors;
    }

    @SuppressWarnings("unchecked")
    private void initialize(EnforcerRuleHelper helper) throws EnforcerRuleException {
        try {
            if (resourceFolders == null) {
                resourceFolders = (List<Resource>) helper.evaluate("${project.build.resources}");
            }
        } catch (ExpressionEvaluationException e) {
            throw new EnforcerRuleException("Unable to lookup an expression " + e.getLocalizedMessage(), e);
        }
    }

    private Element getDatabaseChangelogElement(File absolutFilePath) throws EnforcerRuleException {
        try {
            DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
            Document document = documentBuilder.parse(absolutFilePath);
            document.getDocumentElement().normalize();

            Element databaseChangelogElement = document.getDocumentElement();

            if (ROOT_NODE.equals(databaseChangelogElement.getNodeName())) {
                return databaseChangelogElement;
            }
        } catch (Exception e) {
            throw new EnforcerRuleException("We got an exception.", e);
        }

        return null;
    }

    private boolean isMigrationFolder(File absolutFilePath) {
        File fileFolder = absolutFilePath.getParentFile();

        if (!fileFolder.isDirectory())
            return false;

        File initDbFile = new File(fileFolder.getParentFile(), "initDb.xml");
        if (!initDbFile.exists())
            return false;

        return fileFolder.getName().startsWith("v");
    }

    private String normalizeFilePath(String path) {
        String out = path;

        if (path.contains("\\")) {
            out = out.replace("\\", File.separator);
        }
        if (path.contains("/")) {
            out = out.replace("/", File.separator);
        }

        return out;
    }
    
    private boolean foundLogicalFilePathErrors(EnforcerRuleHelper helper, File absolutFilePath, String relativeFilePath, Element databaseChangelogElement)
            throws EnforcerRuleException {
        String normalizedFilePath = normalizeFilePath(relativeFilePath);
        String normalizedFilePathFromXML = normalizeFilePath(databaseChangelogElement.getAttribute(ATTRIBUTE_LOGICAL_FILE_PATH));
        
        String fileName = absolutFilePath.getName();
        if (fileName.equals("_master.xml") || !isMigrationFolder(absolutFilePath)) {
            if (normalizedFilePathFromXML.equals(normalizedFilePath)) {
                return false;
            }
            helper.getLog().warn(format("Logical file path of file\n\t    [%s]\n\t  is not correct. Found \n\t    [%s]\n\t  and should be\n\t    [%s].", absolutFilePath.getAbsolutePath(),
                    normalizedFilePathFromXML, normalizedFilePath));
            return true;
        }
        
        if (fileName.equals(normalizedFilePathFromXML)) {
            return false;
        }
        helper.getLog().warn(format("Logical file path of file\n\t    [%s]\n\t  is not correct. Found \n\t    [%s]\n\t  and should be\n\t    [%s].", absolutFilePath.getAbsolutePath(),
                databaseChangelogElement.getAttribute(ATTRIBUTE_LOGICAL_FILE_PATH), fileName));
        return true;
    }

    private boolean foundMissingContextes(EnforcerRuleHelper helper, File file, Element databaseChangeLogElement) {
        NodeList nodes = (NodeList) databaseChangeLogElement.getElementsByTagName(NODE_CHANGE_SET);
        for (int i = 0; i < nodes.getLength(); i++) {
            Element item = (Element) nodes.item(i);
            if (!item.hasAttribute(ATTRIBUTE_CONTEXT)) {
                helper.getLog().warn(format("Context is missing in file [%s] and changeset [author=%s] [id=%s].", file.getAbsolutePath(),
                        item.getAttribute("author"), item.getAttribute("id")));
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isCacheable() {
        return false;
    }

    @Override
    public boolean isResultValid(EnforcerRule cachedRule) {
        return false;
    }

    @Override
    public String getCacheId() {
        return null;
    }
}
