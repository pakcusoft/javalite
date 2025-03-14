package org.javalite.app_config;

import org.javalite.common.Convert;
import org.javalite.common.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

/**
 * This class allows configuration of applications for different deployment environments, such as development, test, staging, production, etc.
 * Configuration is done either with property files on the classpath, or on a file system.
 *
 * <h2>1. Classpath configuration</h2>
 *
 * Applications could have environment-specific files, whose names follow this pattern:
 * <code>name.properties</code>, where <code>name</code> is a name of a deployment environment, such as "development",
 * "staging", "production", etc.

 * You can also provide a global file, properties from which will be loaded in all environments: <code>global.properties</code>.
 *
 * <p></p>
 *
 * In all cases the files need to be on the classpath in package <code>/app_config</code>.
 * <p></p>
 *
 * Environment-specific file will have an "environment" part of the file name match to an environment variable called
 * <strong><code>ACTIVE_ENV</code></strong>
 * or system property <strong><code>active_env</code></strong>.
 *
 * <strong>The system property will override the environment variable!</strong>
 * <p></p>
 *
 *
 *
 * Such configuration is easy to achieve in Unix shell:
 *
 * <p></p>
 *
 * <code>
 * export ACTIVE_ENV=test
 * </code>
 *
 * <p></p>
 *
 * If environment variable <code>ACTIVE_ENV</code> is missing, it defaults to "development".
 *
 * <p>
 *     You can also provide an environment as a system property <code>active_env</code>. System property overrides environment
 *     variable <code>ACTIVE_ENV</code>
 * </p>
 * <h3>Example:</h3>
 * If there  are four files packed  into a <code>/app_config</code> package:
 *
 * <ul>
 *     <li>global.properties</li>
 *     <li>development.properties</li>
 *     <li>staging.properties</li>
 *     <li>production.properties</li>
 * </ul>
 * And the <code>ACTIVE_ENV=staging</code>, then properties will be loaded from the following files:
 * <ul>
 *     <li>global.properties</li>
 *     <li>staging.properties</li>
 * </ul>
 *
 * <h2>2. File configuration</h2>
 *
 * In addition to properties on classpath, you can also specify a single file for properties to loaded from a file system.
 * Use a system property with a full path to a file like:
 *
 * <pre>
 *     java -cp $CLASSPATH com.myproject.Main -Dapp_config.properties=/opt/directory1/myproject.properties
 * </pre>
 *
 * <blockquote><strong>The file-based configuration  overrides classpath one. If you have a property defined in both,
 * the classpath configuration will be completely ignored and the file property will be used.</strong></blockquote>
 *
 *
 * <h2>Property substitution</h2>
 *
 * AppConfig allows a property substitution to make it possible to refactor large property files by specifying a
 * repeating value once. If your property file has these properties:
 *
 * <pre>
 * first.name=John
   phrase= And the name is ${first.name}
 * </pre>
 *
 * than this code will print <code>And the name is John</code>:
 *
 * <pre>
 * System.out.println(p("phrase"));
 * </pre>
 *
 * Note: The order of properties does not matter.
 *
 * @author Igor Polevoy
 */
public class AppConfig implements Map<String, String> {

    private static Logger LOGGER = LoggerFactory.getLogger(AppConfig.class);
    private static HashMap<String, Property> props;
    private static boolean isInTestMode = false;

    private static String activeEnv;

    static {
        String envVar = System.getenv("ACTIVE_ENV");
        String sysProp = System.getProperty("active_env");
        if (envVar == null && sysProp == null) {
            activeEnv = "development";
            LOGGER.warn("Environment variable 'ACTIVE_ENV' as well as system property `active_env` are not found, defaulting to 'development' environment.");
        }else if(sysProp != null){
            activeEnv = sysProp;
            LOGGER.warn("Setting environment to: '" + activeEnv + "' from a system property 'active_env'.");
        }else if(envVar != null){
            activeEnv = envVar;
            LOGGER.warn("Setting environment to: '" + activeEnv + "' from an environment variable '" + envVar + "'.");
        }
        init();
    }


    /**
     * You can change the environment dynamically. Attention!!! This method should only be used for tests!
     * Careful out there...
     *
     * @param activeEnv new environment value
     */
    public static void setActiveEnv(String activeEnv){
        AppConfig.activeEnv = activeEnv;
    }

    public static synchronized void init() {
        if (!isInited()){
            reload();
        }
    }

    /**
     * Used in tests.
     */
    public static void reload(){
        try {

            props = new HashMap<>();
            loadFromClasspath();
            String propName = "app_config.properties";
            if(System.getProperties().containsKey(propName)){
                loadFromFileSystem(System.getProperty(propName));
            }

            merge();
        } catch (ConfigInitException e) {
            throw e;
        }catch (Exception e){
            throw new ConfigInitException(e);
        }
    }

    /**
     * Will merge defined values into placeholders like ${placeholder}
     */
    private static void merge() {
        Set<String> keySet = props.keySet();
        for (Property property: props.values()){
            for (String key: keySet){
                if(!key.equals(property.getName())){
                    String val = property.getValue();
                    if(val.contains("${" + key + "}")){
                        property.setValue(val.replaceAll("\\$\\{" + key + "\\}", p(key)));
                    }
                }
            }
        }
    }


    private static void loadFromFileSystem(String filePath) throws MalformedURLException {
        File f = new File(filePath);
        if(!f.exists() || f.isDirectory()){
            throw new ConfigInitException("failed to find file: " + filePath);
        }
        registerProperties(f.toURI().toURL());
    }

    private static void loadFromClasspath(){

        URL globalUrl = AppConfig.class.getResource("/app_config/global.properties");
        if (globalUrl != null) {
            registerProperties(globalUrl);
        }

        String file = "/app_config/" + activeEnv() + ".properties";
        URL url = AppConfig.class.getResource(file);
        if (url == null) {
            LOGGER.warn("Property file not found: '" + file + "'");
        } else {
            registerProperties(url);
        }

        registerMap(System.getenv(), "System.getenv()");
    }

    private static boolean isInited() {
        return props != null;
    }

    private static void registerProperties(URL url) {
        Properties properties = new Properties();

        try {
            properties.load(url.openStream());
        } catch (IOException e) {
            throw new ConfigInitException(e);
        }

        //converting this to <String,String>, Java is annoying sometimes.
        Set<Object> keys = properties.keySet();
        Map<String, String> temp = new HashMap<>();
        for (Object key : keys) {
            temp.put(key.toString(), properties.get(key).toString());
        }
        registerMap(temp, url.toString());
    }

    private static void registerMap(Map<String, String> properties, String source){
        LOGGER.info("Registering properties from: " + source);
        Set<String> keys = properties.keySet();

        for (String key : keys) {
            String value =  properties.get(key);
            Property property = new Property(key, value, source);
            Property previous = props.put(key, property);
            if (previous != null) {
                LOGGER.warn("Duplicate property defined. Property: '" + key + "' found in: "
                        + previous.getPropertyFile() + " and in: " + source
                        + ". Using value '" + property.getValue() + "' from: "
                        + property.getPropertyFile());
            }
        }
    }


    /**
     * Sets a property in memory. If property exists, it will be overwritten, if not, a  new one will be created.
     *
     * @param name  - name of property
     * @param value - value of property
     * @return old value
     */
    public static String setProperty(String name, String value) {
        String val = null;
        if(props.containsKey(name)){
            val = props.get(name).getValue();
        }
        props.put(name, new Property(name, value, "dynamically added"));
        LOGGER.warn("Temporary overriding property: " + name + ". Old value: " + val + ". New value: "  + value);
        return val;
    }

    /**
     * Returns property instance corresponding to key.
     *
     * @param key key for property.
     * @return Property for this key.
     */
    public static Property getAsProperty(String key) {
        if (!isInited()) {
            init();
        }
        return props.get(key);
    }

    /**
     * Returns property value for a key.
     *
     * @param key key of property.
     * @return value for this key, <code>null</code> if not found.
     */
    public static String getProperty(String key) {
        if (!isInited()) {
            init();
        }
        Property p = props.get(key);
        return p == null ? null : p.getValue();
    }


    /**
     * Gets property, synonym for {@link #getProperty(String)}.
     *
     * @param key key of property
     * @return property value
     */
    public static String p(String key) {
        return getProperty(key);
    }


    public static Map<String, String> getAllProperties() {
        if (!isInited()) {
            init();
        }
        HashMap<String, String> plainProps = new HashMap<>();
        for (String name: props.keySet()) {
            plainProps.put(name, props.get(name).getValue());
        }
        return plainProps;
    }


    /////////// Implementation of Map interface below ///////////////////

    @Override
    public int size() {
        return props.size();
    }

    @Override
    public boolean isEmpty() {
        return props.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return props.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return props.containsValue(value);
    }

    @Override
    public String get(Object key) {
        return p(key.toString());
    }

    @Override
    public String put(String key, String value) {
        Object val = props.put(key, new Property(key, value, "added dynamically"));
        return val != null ? val.toString() : null;
    }

    @Override
    public String remove(Object key) {
        Object val = props.remove(key);
        return val != null ? val.toString() : null;
    }

    @Override
    public void putAll(Map<? extends String, ? extends String> m) {
        for(String key: m.keySet()){
            props.put(key, new Property(key, m.get(key), "added dynamically"));
        }
    }

    @Override
    public void clear() {
        props.clear();
    }

    @Override
    public Set<String> keySet() {
        return props.keySet();
    }

    @Override
    public Collection<String> values() {
        List<String> vals = new ArrayList<>();
        for(String key: props.keySet()){
            vals.add(props.get(key).getValue());
        }
        return vals;
    }

    @Override
    public Set<Entry<String, String>> entrySet() {
        Set<Entry<String, String>> entries = new HashSet<>();
        for(String key: props.keySet()){
            entries.add(new AbstractMap.SimpleEntry<>(key, props.get(key).getValue()));
        }
        return entries;
    }


    /**
     * Returns current environment name as defined by environment variable <code>ACTIVE_ENV</code>.
     *
     * @return current environment name as defined by environment variable <code>ACTIVE_ENV</code>.
     */
    public static String activeEnv() {
        return activeEnv;
    }



    /**
     * Checks if running in a context of a test by checking of a presence of a  class <code>org.junit.Test</code> on classpath.
     *
     * @return true if class <code>org.junit.Test</code> is on classpath, otherwise returns <code>false</code>
     */
    public static boolean isInTestMode(){
        if(isInTestMode){
            return true;
        }
        return isInTestMode = Util.getStackTraceString(new Exception()).contains("junit");
    }

    /**
     * @return true if environment name as defined by environment variable <code>ACTIVE_ENV</code> is "testenv".
     */
    public static boolean isInTestEnv() {
        return "testenv".equals(activeEnv());
    }

    /**
     * @return true if environment name as defined by environment variable <code>ACTIVE_ENV</code> is "production".
     */
    public static boolean isInProduction() {
        return "production".equals(activeEnv());
    }

    /**
     * @return true if environment name as defined by environment variable <code>ACTIVE_ENV</code> is "development".
     */
    public static boolean isInDevelopment() {
        return "development".equals(activeEnv());
    }

    /**
     * @return true if environment name as defined by environment variable <code>ACTIVE_ENV</code> is "staging".
     */
    public static boolean isInStaging() {
        return "staging".equals(activeEnv());
    }

    /**
     * Returns all keys that start with a prefix
     *
     * @param prefix prefix for properties.
     */
    public static List<String> getKeys(String prefix) {
        List<String> res = new ArrayList<>();
        for(String key: props.keySet()){
            if(key.startsWith(prefix)){
                res.add(key);
            }
        }
        return res;
    }


    /**
     * Return all numbered properties with a prefix. For instance if there is a file:
     * <pre>
     *     prop.1=one
     *     prop.2=two
     * </pre>
     *
     * .. and this method is called:
     * <pre>
     *     List<String> props = AppConfig.getProperties("prop");
     * </pre>
     * then the resulting list will have all properties starting from <code>prop</code>.
     * This method presumes consecutive numbers in the suffix.
     *
     * @param prefix    prefix of numbered properties.
     * @return list  of property values.
     */
    public static List<String> getProperties(String prefix) {
        List<String> res = new ArrayList<>();
        prefix += ".";
        for (int i = 1; ; i++) {
            String prop = p(prefix + i);
            if (prop == null)
                return res;
            res.add(prop);
        }
    }

    /**
     * Read property as <code>Integer</code>.
     *
     * @param propertyName name of property.
     * @return property as <code>Integer</code>.
     */
    public static Integer pInteger(String propertyName){
        return Convert.toInteger(p(propertyName));
    }

    /**
     * Read property as <code>Double</code>.
     *
     * @param propertyName name of property.
     * @return property as <code>Double</code>.
     */
    public static Double pDouble(String propertyName){
        return Convert.toDouble(p(propertyName));
    }

    /**
     * Read property as <code>Long</code>.
     *
     * @param propertyName name of property.
     * @return property as <code>Long</code>.
     */
    public static Long pLong(String propertyName){
        return Convert.toLong(p(propertyName));
    }

    /**
     * Read property as <code>Float</code>.
     *
     * @param propertyName name of property.
     * @return property as <code>Float</code>.
     */
    public static Float pFloat(String propertyName){
        return Convert.toFloat(p(propertyName));
    }

    /**
     * Read property as <code>Boolean</code>.
     *
     * @param propertyName name of property.
     * @return property as <code>Boolean</code>.
     */
    public static Boolean pBoolean(String propertyName){
        return Convert.toBoolean(p(propertyName));
    }
}
