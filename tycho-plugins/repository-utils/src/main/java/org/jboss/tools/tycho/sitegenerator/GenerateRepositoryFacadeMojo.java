/**
 * Copyright (c) 2012-2014, Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributor:
 *     Mickael Istria (Red Hat, Inc.) - initial API and implementation
 ******************************************************************************/
package org.jboss.tools.tycho.sitegenerator;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.archiver.zip.ZipArchiver;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.io.RawInputStreamFacade;
import org.eclipse.tycho.PackagingType;
import org.eclipse.tycho.ReactorProject;
import org.eclipse.tycho.core.ArtifactDependencyVisitor;
import org.eclipse.tycho.core.FeatureDescription;
import org.eclipse.tycho.core.TychoProject;
import org.eclipse.tycho.core.osgitools.EclipseRepositoryProject;
import org.eclipse.tycho.model.FeatureRef;
import org.eclipse.tycho.model.UpdateSite;
import org.eclipse.tycho.model.UpdateSite.SiteFeatureRef;
import org.eclipse.tycho.packaging.AbstractTychoPackagingMojo;
import org.eclipse.tycho.packaging.UpdateSiteAssembler;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Generates a JBoss-friendly facade and files for this p2 repo
 */
@Mojo(name = "generate-repository-facade", defaultPhase = LifecyclePhase.PACKAGE)
public class GenerateRepositoryFacadeMojo extends AbstractTychoPackagingMojo {
	
	private enum ReferenceStrategy {
		embedReferences,
		compositeReferences
	}

	@Parameter(property = "project", required = true, readonly = true)
	private MavenProject project;

	@Parameter(property = "session", readonly = true)
	private MavenSession session;

	/**
	 * Additional symbols, to replace in files
	 */
	@Parameter
	private Map<String, String> symbols;

	/**
	 * template folder for HTML contents
	 */
	@Parameter
	private File siteTemplateFolder;

	/**
	 * Additional files to add to repo and that are not in the
	 * "siteTemplateFolder". These can be folders.
	 */
	@Parameter
	private List<File> additionalWebResources;

	/**
	 * Additional sites to add to repo associateSites
	 */
	@Parameter
	private List<String> associateSites;
	
	/**
	 * This can have 2 values: embedReferences or compositeReferences.
	 * "embedReferences" will add the repository references directly to the content.jar
	 * of the repository.
	 * "compositeReferences" will add repository references to a new external content.xml
	 * and will create a composite that composite both content and references. Then top-level
	 * repository won't contain any reference to other repo whereas repository in "withreferences"
	 * will composite the top-level repo, with the additional repo adding references to
	 * associateSites  
	 * 
	 * "compositeReferences" is preferred in case your site is used by an upstream project
	 * that will manage the dependencies since its output is actually 2 sites: one without
	 * the references for integrators, and one with references for testers/users who just
	 * want dependencies to come without adding sites, so relying on references.
	 */
	@Parameter(defaultValue="embedReferences")
	private ReferenceStrategy referenceStrategy;

	/**
	 * name of the file in ${siteTemplateFolder} to use as template for
	 * index.html
	 */
	@Parameter(defaultValue = "index.html")
	private String indexName;

	/**
	 * name of the file in ${siteTemplateFolder}/web to use for CSS
	 */
	@Parameter(defaultValue = "site.css")
	private String cssName;

	/**
	 * Whether to remove or not the "Uncategorized" default category
	 */
	@Parameter(defaultValue = "false")
	private boolean removeDefaultCategory;

	@Component(role = TychoProject.class)
	private Map<String, TychoProject> projectTypes;

    /**
     * Whether to skip generation of index.html and associated files
     */
    @Parameter(defaultValue = "false")
    private boolean skipWebContentGeneration;

	@Parameter
	private String p2StatsUrl;

	private File categoryFile;

	public void execute() throws MojoExecutionException, MojoFailureException {
		if (!PackagingType.TYPE_ECLIPSE_REPOSITORY.equals(this.project.getPackaging())) {
			return;
		}
		this.categoryFile = new File(project.getBasedir(), "category.xml");
		if (!this.categoryFile.isFile()) {
			// happens in case of definition based on .product
			return;
		}

		if (this.symbols == null) {
			this.symbols = new HashMap<String, String>();
		}
		// TODO populate default symbols: ${update.site.name} &
		// ${update.site.description}

		File outputRepository = new File(this.project.getBuild().getDirectory(), "repository");

		// If a siteTemplateFolder is set, pull index.html and site.css from
		// there; otherwise use defaults
        if (!skipWebContentGeneration) {
            try {
                copyTemplateResources(outputRepository);
            } catch (Exception ex) {
                throw new MojoExecutionException("Error while copying siteTemplateFolder content to " + outputRepository, ex);
            }
            if (this.additionalWebResources != null) {
                for (File resource : this.additionalWebResources) {
                    try {
                        if (resource.isDirectory()) {
                            FileUtils.copyDirectoryStructure(resource, new File(outputRepository, resource.getName()));
                        } else if (resource.isFile()) {
                            FileUtils.copyFile(resource, new File(outputRepository, resource.getName()));
                        }
                    } catch (Exception ex) {
                        throw new MojoExecutionException("Error while copying resource " + resource.getPath(), ex);
                    }
                }
            }

            File outputSiteXml = generateSiteXml(outputRepository);
            if (new File(outputRepository, "features").isDirectory()) { //$NON-NLS-1$
                generateSiteProperties(outputRepository, outputSiteXml);
            }
            generateWebStuff(outputRepository, outputSiteXml);
		}
		try {
			alterContentJar(outputRepository);
		} catch (Exception ex) {
			throw new MojoExecutionException("Error while altering content.jar", ex);
		}
		if (this.p2StatsUrl != null) {
			try {
				addP2Stats(outputRepository);
			} catch (Exception ex) {
				throw new MojoExecutionException("Error while adding p2.stats to repository", ex);
			}
		}
		if (this.associateSites != null && !this.associateSites.isEmpty() && this.referenceStrategy == ReferenceStrategy.compositeReferences) {
			try {
				createCompositeReferences(outputRepository, this.associateSites);
			} catch (IOException ex) {
				throw new MojoFailureException(ex.getMessage(), ex);
			}
		}

		File repoZipFile = new File(this.project.getBuild().getDirectory(), this.project.getArtifactId() + "-" + this.project.getVersion() + ".zip");
		repoZipFile.delete();
		ZipArchiver archiver = new ZipArchiver();
		archiver.setDestFile(repoZipFile);
		archiver.setForced(true);
		archiver.addDirectory(outputRepository);
		try {
			archiver.createArchive();
		} catch (IOException ex) {
			throw new MojoFailureException("Could not create " + repoZipFile.getName(), ex);
		}

	}

	private void createCompositeReferences(File outputRepository, List<String> associateSites2) throws IOException {
		long timestamp = System.currentTimeMillis();
		String repoName = this.project.getName();
		if (repoName == null) {
			repoName = this.project.getArtifactId();
		}
		File referencesDir = new File(outputRepository, "references");
		referencesDir.mkdir();
		File contentXmlReference = new File(referencesDir, "content.xml");
		StringBuilder content = new StringBuilder();
		content
			.append("<?xml version='1.0' encoding='UTF-8' standalone='yes'?>").append('\n')
			.append("<?metadataRepository version='1.1.0'?>").append('\n')
			.append("<repository name='References for").append(repoName).append("' type='org.eclipse.equinox.internal.p2.metadata.repository.LocalMetadataRepository' version='1'>").append('\n')
			.append("  <properties size='1'>").append('\n')
		    .append("    <property name='p2.timestamp' value='").append(timestamp).append("'/>").append('\n')
		    .append("  </properties>").append('\n')
		    .append("  <references size='").append(2 * associateSites2.size()).append("'>").append('\n');
		for (String site : associateSites2) {
			content.append("      <repository options='1' type='0' uri='").append(site).append("' url='").append(site).append("'/>").append('\n');
			content.append("      <repository options='1' type='1' uri='").append(site).append("' url='").append(site).append("'/>").append('\n');
		}
		content.append("  </references>").append('\n');
		content.append("</repository>");
		org.apache.commons.io.FileUtils.writeStringToFile(contentXmlReference, content.toString());
		
		File compositeWithRefDir = new File(outputRepository, "withreferences");
		compositeWithRefDir.mkdir();
		{
			File compositeContentXml = new File(compositeWithRefDir, "compositeContent.xml");
			StringBuilder compositeContent = new StringBuilder();
			compositeContent.append("<?compositeMetadataRepository version='1.0.0'?>").append('\n')
				.append("<repository name='").append(repoName).append("' type='org.eclipse.equinox.internal.p2.metadata.repository.CompositeMetadataRepository' version='1.0.0'>").append('\n')
				.append("  <properties size='2'>").append('\n')
			    .append("    <property name='p2.compressed' value='true'/>").append('\n')
			    .append("    <property name='p2.timestamp' value='").append(timestamp).append("'/>").append('\n')
			    .append("  </properties>").append("\n")
			    .append("  <children size='2'>").append("\n")
			    .append("    <child location='../'/>").append('\n')
			    .append("    <child location='../references'/>").append('\n')
			    .append("  </children>").append('\n')
			    .append("</repository>");
			org.apache.commons.io.FileUtils.writeStringToFile(compositeContentXml, compositeContent.toString());
		}
		{
			File compositeArtifactsXml = new File(compositeWithRefDir, "compositeArtifacts.xml");
			StringBuilder compositeArtifact = new StringBuilder();
			compositeArtifact.append("<?compositeArtifactRepository version='1.0.0'?>").append('\n')
				.append("<repository name='").append(repoName).append("' type='org.eclipse.equinox.internal.p2.artifact.repository.CompositeArtifactRepository' version='1.0.0'>").append('\n')
				.append("  <properties size='2'>").append('\n')
			    .append("    <property name='p2.compressed' value='true'/>").append('\n')
			    .append("    <property name='p2.timestamp' value='").append(timestamp).append("'/>").append('\n')
			    .append("  </properties>").append("\n")
			    .append("  <children size='1'>").append("\n")
			    .append("    <child location='../'/>").append('\n')
			    .append("  </children>").append('\n')
			    .append("</repository>");
			org.apache.commons.io.FileUtils.writeStringToFile(compositeArtifactsXml, compositeArtifact.toString());
		}
	}

	private void generateWebStuff(File outputRepository, File outputSiteXml) throws TransformerFactoryConfigurationError, MojoExecutionException {
		// Generate index.html
		try {
			InputStream siteXsl = getClass().getResourceAsStream("/xslt/site.xsl");
			Source xsltSource = new StreamSource(siteXsl);
			Transformer transformer = TransformerFactory.newInstance().newTransformer(xsltSource);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			Result res = new StreamResult(out);
			transformer.transform(new StreamSource(outputSiteXml), res);
			siteXsl.close();
			this.symbols.put("${site.contents}", out.toString());
			out.close();
		} catch (Exception ex) {
			throw new MojoExecutionException("Error occured while generating 'site.xsl'", ex);
		}
		try {
			alterIndexFile(outputRepository);
		} catch (Exception ex) {
			throw new MojoExecutionException("Error writing file " + indexName, ex);
		}
	}

	private void generateSiteProperties(File outputRepository, File outputSiteXml) throws TransformerFactoryConfigurationError, MojoExecutionException {
		// Generate site.properties
		try {
			InputStream siteXsl = getClass().getResourceAsStream("/xslt/site.properties.xsl");
			Source xsltSource = new StreamSource(siteXsl);
			Transformer transformer = TransformerFactory.newInstance().newTransformer(xsltSource);
			FileOutputStream out = new FileOutputStream(new File(outputRepository, "site.properties"));
			Result res = new StreamResult(out);
			transformer.transform(new StreamSource(outputSiteXml), res);
			siteXsl.close();
			out.close();
		} catch (Exception ex) {
			throw new MojoExecutionException("Error occured while generating 'site.properties'", ex);
		}
	}

	/*
	 * We'll stop creating site.xml ASAP, but it's still use to generate list of features
	 */
	@Deprecated
	private File generateSiteXml(File outputRepository) throws MojoExecutionException {
		// Generate site.xml
		UpdateSite site = null;
		try {
			site = UpdateSite.read(this.categoryFile);
		} catch (IOException ex) {
			throw new MojoExecutionException("Could not read 'category.xml' file", ex);
		}
		new EclipseRepositoryProject().getDependencyWalker(this.project).traverseUpdateSite(site, new ArtifactDependencyVisitor() {
			@Override
			public boolean visitFeature(FeatureDescription feature) {
				FeatureRef featureRef = feature.getFeatureRef();
				String id = featureRef.getId();
				ReactorProject otherProject = feature.getMavenProject();
				String version;
				if (otherProject != null) {
					version = otherProject.getExpandedVersion();
				} else {
					version = feature.getKey().getVersion();
				}
				String url = UpdateSiteAssembler.FEATURES_DIR + id + "_" + version + ".jar";
				((SiteFeatureRef) featureRef).setUrl(url);
				featureRef.setVersion(version);
				return false; // don't traverse included features
			}
		});

		File outputSiteXml = new File(outputRepository, "site.xml");
		try {
			if (!outputSiteXml.exists()) {
				outputSiteXml.createNewFile();
			}
			UpdateSite.write(site, outputSiteXml);
		} catch (IOException ex) {
			ex.printStackTrace();
			throw new MojoExecutionException("Could not write site.xml to '" + outputSiteXml.getAbsolutePath() + "'", ex);
		}
		return outputSiteXml;
	}

	private void alterContentJar(File p2repository) throws FileNotFoundException, IOException, SAXException, ParserConfigurationException, TransformerFactoryConfigurationError,
			TransformerConfigurationException, TransformerException {
		File contentJar = new File(p2repository, "content.jar");
		ZipInputStream contentStream = new ZipInputStream(new FileInputStream(contentJar));
		ZipEntry entry = null;
		Document contentDoc = null;
		boolean done = false;
		while (!done && (entry = contentStream.getNextEntry()) != null) {
			if (entry.getName().equals("content.xml")) {
				contentDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(contentStream);
				Element repoElement = (Element) contentDoc.getElementsByTagName("repository").item(0);
				{
					NodeList references = repoElement.getElementsByTagName("references");
					// remove default references
					for (int i = 0; i < references.getLength(); i++) {
						Node currentRef = references.item(i);
						currentRef.getParentNode().removeChild(currentRef);
					}
					// add assiciateSites
					if (this.associateSites != null && this.associateSites.size() > 0 && this.referenceStrategy == ReferenceStrategy.embedReferences) {
						Element refElement = contentDoc.createElement("references");
						refElement.setAttribute("size", Integer.valueOf(2 * associateSites.size()).toString());
						for (String associate : associateSites) {
							Element rep0 = contentDoc.createElement("repository");
							rep0.setAttribute("uri", associate);
							rep0.setAttribute("url", associate);
							rep0.setAttribute("type", "0");
							rep0.setAttribute("options", "1");
							refElement.appendChild(rep0);
							Element rep1 = (Element) rep0.cloneNode(true);
							rep1.setAttribute("type", "1");
							refElement.appendChild(rep1);
						}
						repoElement.appendChild(refElement);
					}
				}
				if (this.removeDefaultCategory) {
					Element unitsElement = (Element) repoElement.getElementsByTagName("units").item(0);
					NodeList units = unitsElement.getElementsByTagName("unit");
					for (int i = 0; i < units.getLength(); i++) {
						Element unit = (Element) units.item(i);
						String id = unit.getAttribute("id");
						if (id != null && id.contains(".Default")) {
							unit.getParentNode().removeChild(unit);
						}
					}
					unitsElement.setAttribute("size", Integer.toString(unitsElement.getElementsByTagName("unit").getLength()));
				}
				done = true;
			}
		}
		// .close and .closeEntry raise exception:
		// https://issues.apache.org/bugzilla/show_bug.cgi?id=3862
		ZipOutputStream outContentStream = new ZipOutputStream(new FileOutputStream(contentJar));
		ZipEntry contentXmlEntry = new ZipEntry("content.xml");
		outContentStream.putNextEntry(contentXmlEntry);
		Transformer transformer = TransformerFactory.newInstance().newTransformer();
		transformer.setOutputProperty(OutputKeys.INDENT, "yes");
		transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
		transformer.setOutputProperty(OutputKeys.STANDALONE, "yes");
		DOMSource source = new DOMSource(contentDoc);
		StreamResult result = new StreamResult(outContentStream);
		transformer.transform(source, result);
		contentStream.close();
		outContentStream.closeEntry();
		outContentStream.close();
	}

	/**
	 * Add p2 stats to the repository See
	 * http://wiki.eclipse.org/Equinox_p2_download_stats
	 *
	 * @param p2repository
	 */
	private void addP2Stats(File p2repository) throws FileNotFoundException, IOException, SAXException, ParserConfigurationException, TransformerFactoryConfigurationError,
			TransformerConfigurationException, TransformerException {
		File artifactsJar = new File(p2repository, "artifacts.jar");
		ZipInputStream contentStream = new ZipInputStream(new FileInputStream(artifactsJar));
		ZipEntry entry = null;
		Document contentDoc = null;
		boolean done = false;
		while (!done && (entry = contentStream.getNextEntry()) != null) {
			if (entry.getName().equals("artifacts.xml")) {
				contentDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(contentStream);
				Element repoElement = (Element) contentDoc.getElementsByTagName("repository").item(0);
				// Add p2.StatsURI property
				Element repoProperties = (Element) contentDoc.getElementsByTagName("properties").item(0);
				int newRepoPropertiesSize = Integer.parseInt(repoProperties.getAttribute("size")) + 1;
				repoProperties.setAttribute("size", Integer.toString(newRepoPropertiesSize));
				Element p2statsElement = contentDoc.createElement("property");
				p2statsElement.setAttribute("name", "p2.statsURI");
				p2statsElement.setAttribute("value", this.p2StatsUrl);
				repoProperties.appendChild(p2statsElement);
				// process features
				NodeList artifacts = ((Element) repoElement.getElementsByTagName("artifacts").item(0)).getElementsByTagName("artifact");
				for (int i = 0; i < artifacts.getLength(); i++) {
					Element currentArtifact = (Element) artifacts.item(i);
					if (currentArtifact.getAttribute("classifier").equals("org.eclipse.update.feature")) {
						String iu = currentArtifact.getAttribute("id");
						Element artifactProperties = (Element) currentArtifact.getElementsByTagName("properties").item(0);
						int newArtifactPropertiesSize = Integer.parseInt(artifactProperties.getAttribute("size")) + 1;
						artifactProperties.setAttribute("size", Integer.toString(newArtifactPropertiesSize));
						Element statsElement = contentDoc.createElement("property");
						statsElement.setAttribute("name", "download.stats");
						statsElement.setAttribute("value", iu);
						artifactProperties.appendChild(statsElement);
					}
				}
				done = true;
			}
		}
		// .close and .closeEntry raise exception:
		// https://issues.apache.org/bugzilla/show_bug.cgi?id=3862
		ZipOutputStream outContentStream = new ZipOutputStream(new FileOutputStream(artifactsJar));
		ZipEntry contentXmlEntry = new ZipEntry("artifacts.xml");
		outContentStream.putNextEntry(contentXmlEntry);
		Transformer transformer = TransformerFactory.newInstance().newTransformer();
		transformer.setOutputProperty(OutputKeys.INDENT, "yes");
		transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
		DOMSource source = new DOMSource(contentDoc);
		StreamResult result = new StreamResult(outContentStream);
		transformer.transform(source, result);
		contentStream.close();
		outContentStream.closeEntry();
		outContentStream.close();
	}

	private void alterIndexFile(File outputSite) throws FileNotFoundException, IOException {
		File templateFile = new File(outputSite, this.indexName);
		FileInputStream fis = new FileInputStream(templateFile);
		String htmlFile = IOUtil.toString(fis, "UTF-8");
		for (Entry<String, String> entry : this.symbols.entrySet()) {
			String key = entry.getKey();
			if (!key.startsWith("${")) {
				key = "${" + key + "}";
			}
			if (entry.getValue() != null) {
				htmlFile = htmlFile.replace(key, entry.getValue());
			}
		}
		FileOutputStream out = new FileOutputStream(templateFile);
		out.write(htmlFile.getBytes("UTF-8"));
		fis.close();
		out.close();
	}

	private void copyTemplateResources(File outputSite) throws IOException, MojoExecutionException {
		getLog().debug("Using outputSite = " + outputSite);
		getLog().debug("Using siteTemplateFolder = " + this.siteTemplateFolder);
		if (this.siteTemplateFolder != null) {
			if (!this.siteTemplateFolder.isDirectory()) {
				throw new MojoExecutionException("'siteTemplateFolder' not correctly set. " + this.siteTemplateFolder.getAbsolutePath() + " is not a directory");
			}
			FileUtils.copyDirectoryStructure(this.siteTemplateFolder, outputSite);

			// verify we have everything we need after copying from the
			// siteTemplateFolder
			if (!new File(outputSite, this.indexName).isFile()) {
				// copy default index
				getLog().warn("No " + this.siteTemplateFolder + "/" + this.indexName + " found; using default.");
				InputStream indexStream = getClass().getResourceAsStream("/index.html");
				FileUtils.copyStreamToFile(new RawInputStreamFacade(indexStream), new File(outputSite, this.indexName));
				indexStream.close();
			}
			File webFolder = new File(outputSite, "web");
			if (!webFolder.exists()) {
				webFolder.mkdir();
			}
			if (!new File(webFolder, this.cssName).isFile()) {
				// copy default css
				getLog().warn("No " + webFolder + "/" + this.cssName + " found; using default.");
				InputStream cssStream = getClass().getResourceAsStream("/web/" + this.cssName);
				FileUtils.copyStreamToFile(new RawInputStreamFacade(cssStream), new File(webFolder, this.cssName));
				cssStream.close();
			}
		} else {
			// copy default index
			InputStream indexStream = getClass().getResourceAsStream("/index.html");
			FileUtils.copyStreamToFile(new RawInputStreamFacade(indexStream), new File(outputSite, this.indexName));
			indexStream.close();
			File webFolder = new File(outputSite, "web");
			if (!webFolder.exists()) {
				webFolder.mkdir();
			}
			// copy default css
			InputStream cssStream = getClass().getResourceAsStream("/web/" + this.cssName);
			FileUtils.copyStreamToFile(new RawInputStreamFacade(cssStream), new File(webFolder, this.cssName));
			cssStream.close();
		}
	}
}
