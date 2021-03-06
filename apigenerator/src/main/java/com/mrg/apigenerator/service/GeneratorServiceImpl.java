package com.mrg.apigenerator.service;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import javax.lang.model.element.Modifier;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;
import org.codehaus.plexus.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.stereotype.Service;

import com.mrg.apigenerator.domain.DataSource;
import com.mrg.apigenerator.domain.EntityInformation;
import com.mrg.apigenerator.domain.Filter;
import com.mrg.apigenerator.domain.MEntity;
import com.mrg.apigenerator.exception.EntityGenerationException;
import com.mrg.apigenerator.repository.DataSourceRepository;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

/**
 * @author M.Rasid Gencosmanoglu
 *
 */
@Service
public class GeneratorServiceImpl implements GeneratorService {

	private DataSourceRepository dataSourceRepository;

	private InvocationRequest invocationRequest;
	private Invoker invoker;
	private static final Logger log = LoggerFactory.getLogger(GeneratorServiceImpl.class);

	private static final String PROJECT_ROOT_PATH = "C:\\Users\\mehme\\git\\apigenerator-template\\apigenerator-template\\src\\main\\java";
	private static final String APP_PROPERTIES_PATH = "C:\\Users\\mehme\\git\\apigenerator-template\\apigenerator-template\\src\\main\\resources\\application.properties";
	private static final String HIBERNATE_PROPERTIES_PATH = "C:\\Users\\mehme\\git\\apigenerator-template\\apigenerator-template\\src\\main\\resources\\hibernate.properties";

	@Autowired
	public GeneratorServiceImpl(DataSourceRepository dataSourceRepository) {
		this.dataSourceRepository = dataSourceRepository;
		this.invocationRequest = new DefaultInvocationRequest();
		this.invoker = new DefaultInvoker();
	}

	@Override
	public List<EntityInformation> generateEntities(String entityPath, String pomfile, String packageName)
			throws EntityGenerationException {

		DataSource dataSource = dataSourceRepository.findFirstByIsGeneratedOrderByProcessDateDesc(false);
		if (dataSource == null) {
			log.info("DataSource is null..");
			return null;
		}
		byte[] properties = null;
		try {

			// Cleaning pregenerated entities
			File f = new File(entityPath);
			if (f.exists() && f.isDirectory()) {
				FileUtils.cleanDirectory(entityPath);
			}
			// CREATING APP.PROPERTIES
			// writing datasource info to the application.properties for Spring
			Files.deleteIfExists(Paths.get(APP_PROPERTIES_PATH));
			properties = dataSource.getAppProperties();
			writeToProperties(APP_PROPERTIES_PATH, properties);
			log.info("application.properties file is generated!");

			// CREATING HIBERNATE.PROPERTIES
			// writing datasource info to the hibernate.properties for Hibernate
			properties = dataSource.getHibernateProperties();
			writeToProperties(HIBERNATE_PROPERTIES_PATH, properties);
			log.info("hibernate.properties file is generated!");

			// CREATING ENTITIES
			// generating entity classes by running mvn antrun:run@hbm2java
			invocationRequest.setPomFile(
					new File("C:\\Users\\mehme\\git\\apigenerator-template\\apigenerator-template\\" + pomfile));
			invocationRequest.setGoals(Collections.singletonList("antrun:run@hbm2java"));

			invoker.setMavenHome(new File("D:\\Maven\\apache-maven-3.6.1"));
			invoker.execute(invocationRequest);
			log.info("entities are generated!");

			Files.deleteIfExists(Paths.get(HIBERNATE_PROPERTIES_PATH));

			return findNewEntities(entityPath, packageName);

		} catch (Exception e) {
			log.error("Error occured while generating source files : " + e.getMessage() + e.getStackTrace());
			throw new EntityGenerationException("Error occured while generating source files");
		}
	}

	@Override
	public List<EntityInformation> findNewEntities(String entityPath, String packageName) {

		File entityRootFolder = new File(entityPath);

		FilenameFilter filter = (dir, name) -> dir.isDirectory() && name.toLowerCase().endsWith(".java");

		List<EntityInformation> newEntityList = new ArrayList<EntityInformation>();
		List<File> fileList = Arrays.asList(entityRootFolder.listFiles(filter));

		if (fileList != null && fileList.size() > 0) {

			for (File file : fileList) {
				EntityInformation entity = new EntityInformation();
				String fileName = file.getName().replaceFirst("[.][^.]+$", "");
				try {

					Field[] fields = loadJavaFileToClassPath(file, fileName, packageName).getDeclaredFields();

					HashMap<String, String> entityFields = new HashMap<>();
					for (Field entityField : fields) {
						entityFields.put(entityField.getName(), entityField.getType().getName().toString());
					}
					entity.setEntityName(fileName);
					entity.setFields(entityFields);
					newEntityList.add(entity);

				} catch (Exception e) {
					log.error("Error occured while invoking findNewEntities method : " + e.getMessage()
							+ e.getStackTrace());
				}

			}
		}
		return newEntityList;
	}
	
	

	// TODO : database vendor a göre repository id değeri değişebilir mi? (MySql ->
	// Integer, Oracle -> Long)
	@Override
	public List<MEntity> generateRepositories(List<MEntity> newEntityList /* String databaseVendor */) {

		List<MEntity> entityList = new ArrayList<MEntity>();
		try {
			for (MEntity entity : newEntityList) {
				// Generating repository source files for each entity
				String serviceName = entity.getEntityName() + "Repository";
				TypeSpec entityRepository = TypeSpec.interfaceBuilder(serviceName)
						.addAnnotation(RepositoryRestResource.class).addModifiers(Modifier.PUBLIC)
						.addMethods(createMethods(entity))
						.addSuperinterface(ParameterizedTypeName.get(ClassName.get(PagingAndSortingRepository.class),
								ClassName.get("com.mrg.webapi.model", entity.getEntityName()),
								ClassName.get(Integer.class)))
						.build();
				Path filePath = Paths.get(PROJECT_ROOT_PATH);
				JavaFile javaFile = JavaFile.builder("com.mrg.webapi.repository", entityRepository).build();
				javaFile.writeTo(filePath);
				entityList.add(entity);
				
				

				log.info(entity.getEntityName() + "Repository is generated...");
			}
			return entityList;

		} catch (Exception ex) {
			log.error("Error occured while deploying application : " + ex.getMessage() + ex.getStackTrace());
		}
		return entityList;

	}

	private Class loadJavaFileToClassPath(File file, String fileName, String packageName) throws Exception {

		ClassLoader cl;
		Class cls;

		File projectRootFolder = new File(PROJECT_ROOT_PATH);
		// Compiling .java to .class files
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		compiler.run(null, null, null, file.getPath());

		// Loading .class files
		URL[] urls = new URL[] { projectRootFolder.toURI().toURL() };
		cl = new URLClassLoader(urls);
		cls = cl.loadClass(packageName + fileName);

		return cls;

	}

	private Iterable<MethodSpec> createMethods(MEntity entity) {

		List<MethodSpec> methodList = new ArrayList<MethodSpec>();

		for (Filter filter : entity.getFilterList()) {

			ClassName model = ClassName.get("com.mrg.webapi.model", entity.getEntityName());
			ClassName list = ClassName.get("java.util", "List");
			TypeName listOfEntities = ParameterizedTypeName.get(list, model);

			// IS, EQUALS
			if (filter.getName().equals("Is") || filter.getName().equals("Equals")) {
				MethodSpec andMethod = MethodSpec.methodBuilder("findBy" + filter.getFields()[0] + filter.getName())
						.returns(listOfEntities)
						.addParameter(filter.getName().equals("Is") ? String.class : Integer.class,
								filter.getFields()[0].toLowerCase())
						.addModifiers(Modifier.ABSTRACT, Modifier.PUBLIC).build();
				methodList.add(andMethod);
			}

			// AND, OR
			if (filter.getName().equals("And") || filter.getName().equals("Or")) {
				MethodSpec andMethod = MethodSpec
						.methodBuilder("findBy" + filter.getFields()[0] + filter.getName() + filter.getFields()[1])
						.returns(listOfEntities).addParameter(String.class, filter.getFields()[0].toLowerCase())
						.addParameter(String.class, filter.getFields()[1].toLowerCase())
						.addModifiers(Modifier.ABSTRACT, Modifier.PUBLIC).build();
				methodList.add(andMethod);
			}

			// AFTER, BEFORE
			if (filter.getName().equals("After") || filter.getName().equals("Before")) {
				MethodSpec andMethod = MethodSpec.methodBuilder("findBy" + filter.getFields()[0] + filter.getName())
						.returns(listOfEntities).addParameter(Date.class, filter.getFields()[0].toLowerCase())
						.addModifiers(Modifier.ABSTRACT, Modifier.PUBLIC).build();
				methodList.add(andMethod);
			}

			// ISNULL, ISNOTNULL
			if (filter.getName().equals("IsNull") || filter.getName().equals("IsNotNull")) {
				MethodSpec andMethod = MethodSpec.methodBuilder("findBy" + filter.getFields()[0] + filter.getName())
						.returns(listOfEntities).addModifiers(Modifier.ABSTRACT, Modifier.PUBLIC).build();
				methodList.add(andMethod);
			}

			// LESSTHAN, LESSTHANEQUAL, GREATERTHAN, GREATERTHANEQUAL
			if (filter.getName().equals("lessthan") || filter.getName().equals("lessthanequal")
					|| filter.getName().equals("greaterthan") || filter.getName().equals("greaterthanequal")) {
				MethodSpec andMethod = MethodSpec.methodBuilder("findBy" + filter.getFields()[0] + filter.getName())
						.returns(listOfEntities).addParameter(Integer.class, filter.getFields()[0].toLowerCase())
						.addModifiers(Modifier.ABSTRACT, Modifier.PUBLIC).build();
				methodList.add(andMethod);
			}

		}

		return methodList;

	}

	@Override
	public void deploy() throws MavenInvocationException, IOException {

		// TODO : findByProjectName(projectName)
		DataSource dataSource = dataSourceRepository.findFirstByIsGeneratedOrderByProcessDateDesc(false);
		if (dataSource == null) {
			log.info("DataSource is null..");
			return;
		}
		// CONFIGURATION - try multiple datasource manually
		// TODO : generate Configuration beans for multiple datasources
		// generateDataSourceConfigBeans();
		// log.info("configuration beans for multiple datasources are generated!");

		// Updating datasource info by setting generated column true
		dataSource.setIsGenerated(true);
		dataSourceRepository.save(dataSource);

		// TODO : Setup a CI/CD pipeline, instead of generating and executing a fat jar.
		// Generating a fat jar for webapi
		invocationRequest.setGoals(Collections.singletonList("package"));
		invoker.execute(invocationRequest);
		log.info("webapi jar is generated..");
		// Executing webapi jar
		Runtime.getRuntime().exec("java -jar /Users/mrgenco/Documents/MRG/webapi/target/webapi-0.0.1.jar");
		log.info("webapi application is running..");

	}

	private void writeToProperties(String propertyPath, byte[] properties) throws IOException {

		try {

			if (propertyPath.equals(HIBERNATE_PROPERTIES_PATH)) {

				File hibernatePropertiesFile = new File(HIBERNATE_PROPERTIES_PATH);
				if (hibernatePropertiesFile.createNewFile()) {
					log.info("hibernate.properties file is generated!");
				} else {
					log.info("hibernate.properties file is already exist!");
				}
				Files.write(hibernatePropertiesFile.toPath(), properties, StandardOpenOption.APPEND);

			}
			if (propertyPath.equals(APP_PROPERTIES_PATH)) {

				File appPropertiesFile = new File(APP_PROPERTIES_PATH);
				if (appPropertiesFile.createNewFile()) {
					log.info("application.properties file is generated!");
				} else {
					log.info("application.properties file is already exist!");
				}
				Files.write(appPropertiesFile.toPath(), properties, StandardOpenOption.APPEND);
			}
		} catch (Exception ex) {
			log.error("Error occured while writing properties: " + ex.getMessage() + ex.getStackTrace());
			throw ex;
		}

	}

}
