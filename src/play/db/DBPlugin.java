package play.db;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.sql.DataSource;

import jregex.Matcher;

import org.apache.commons.lang.StringUtils;

import play.Logger;
import play.Play;
import play.PlayPlugin;
import play.exceptions.DatabaseException;
import play.mvc.Http;
import play.mvc.Http.Request;
import play.mvc.Http.Response;

import com.mchange.v2.c3p0.ComboPooledDataSource;
import com.mchange.v2.c3p0.ConnectionCustomizer;

/**
 * The DB plugin
 */
public class DBPlugin extends PlayPlugin {

    public static String url = "";
    org.h2.tools.Server h2Server;

    @Override
    public boolean rawInvocation(Request request, Response response) throws Exception {
        if (Play.mode.isDev() && request.path.equals("/@db")) {
            response.status = Http.StatusCode.FOUND;
            String serverOptions[] = new String[] { };

            // For H2 embeded database, we'll also start the Web console
            if (h2Server != null) {
                h2Server.stop();
            }

            String domain = request.domain;
            if (domain.equals("")) {
                domain = "localhost";
            }

            if (!domain.equals("localhost")) {
                serverOptions = new String[] {"-webAllowOthers"};
            }
            
            h2Server = org.h2.tools.Server.createWebServer(serverOptions);
            h2Server.start();

            response.setHeader("Location", "http://" + domain + ":8082/");
            return true;
        }
        return false;
    }

    @Override
    public void onApplicationStart() {
        if (changed()) {
            try {

                Properties p = Play.configuration;

                if (DB.datasource != null) {
                    DB.destroy();
                }

	        boolean isJndiDatasource = false;
                String datasourceName = p.getProperty("db", "");
                // Identify datasource JNDI lookup name by 'jndi:' or 'java:' prefix 
                if (datasourceName.startsWith("jndi:")) {
                    datasourceName = datasourceName.substring("jndi:".length());
                    isJndiDatasource = true;
                }

                if (isJndiDatasource || datasourceName.startsWith("java:")) {

                    Context ctx = new InitialContext();
                    DB.datasource = (DataSource) ctx.lookup(datasourceName);

                } else {

                    // Try the driver
                    String driver = p.getProperty("db.driver");
                    try {
                        Driver d = (Driver) Class.forName(driver, true, Play.classloader).newInstance();
                        DriverManager.registerDriver(new ProxyDriver(d));
                    } catch (Exception e) {
                        throw new Exception("Driver not found (" + driver + ")");
                    }

                    // Try the connection
                    Connection fake = null;
                    try {
                        if (p.getProperty("db.user") == null) {
                            fake = DriverManager.getConnection(p.getProperty("db.url"));
                        } else {
                            fake = DriverManager.getConnection(p.getProperty("db.url"), p.getProperty("db.user"), p.getProperty("db.pass"));
                        }
                    } finally {
                        if (fake != null) {
                            fake.close();
                        }
                    }

                    System.setProperty("com.mchange.v2.log.MLog", "com.mchange.v2.log.FallbackMLog");
                    System.setProperty("com.mchange.v2.log.FallbackMLog.DEFAULT_CUTOFF_LEVEL", "OFF");
                    ComboPooledDataSource ds = new ComboPooledDataSource();
                    ds.setDriverClass(p.getProperty("db.driver"));
                    ds.setJdbcUrl(p.getProperty("db.url"));
                    ds.setUser(p.getProperty("db.user"));
                    ds.setPassword(p.getProperty("db.pass"));
                    //连接池在获得新连接失败时重试的次数，如果小于等于0则无限重试直至连接获得成功,default:30
                    ds.setAcquireRetryAttempts(Integer.parseInt(p.getProperty("db.pool.acquireRetryAttempts", "30")));
                    //连接池在无空闲连接可用时一次性创建的新数据库连接数,default:3 
                    ds.setAcquireIncrement(Integer.parseInt(p.getProperty("db.pool.acquireIncrement", "3")));
                    //用来配置测试空闲连接的间隔时间。测试方式还是上面的两种之一，可以用来解决MySQL8小时断开连接的问题。因为它
                    //保证连接池会每隔一定时间对空闲连接进行一次测试，从而保证有效的空闲连接能每隔一定时间访问一次数据库，将于MySQL
                    //8小时无会话的状态打破,为0则不测试,default:0
                    ds.setIdleConnectionTestPeriod(Integer.parseInt(p.getProperty("db.pool.idleConnectionTestPeriod", "0")));
                    //连接池中保留的最大连接数,default:15
                    ds.setMaxPoolSize(Integer.parseInt(p.getProperty("db.pool.maxSize", "15")));
                    //连接池中保留的最小连接数,default :3
                    ds.setMinPoolSize(Integer.parseInt(p.getProperty("db.pool.minSize", "3")));
                    //连接的最大空闲时间，如果超过这个时间，某个数据库连接还没有被使用，则会断开掉这个连接
                    //如果为0，则永远不会断开连接,default:0
                    ds.setMaxIdleTime(Integer.parseInt(p.getProperty("db.pool.maxIdleTime", "0")));
                    //这个配置主要是为了减轻连接池的负载，比如连接池中连接数因为某次数据访问高峰导致创建了很多数据连接
                    //但是后面的时间段需要的数据库连接数很少，则此时连接池完全没有必要维护那么多的连接，所以有必要将
                    //断开丢弃掉一些连接来减轻负载，必须小于maxIdleTime。配置不为0，则会将连接池中的连接数量保持到minPoolSize。
                    //为0则不处理,default:0
                    ds.setMaxIdleTimeExcessConnections(Integer.parseInt(p.getProperty("db.pool.maxIdleTimeExcessConnections", "0")));
                    //配置当连接池所有连接用完时应用程序getConnection的等待时间。为0则无限等待直至有其他连接释放
                    //或者创建新的连接，不为0则当时间到的时候如果仍没有获得连接，则会抛出SQLException,default:0,单位:毫秒
                    ds.setCheckoutTimeout(Integer.parseInt(p.getProperty("db.pool.timeout", "0")));
                    //连接池初始化时创建的连接数,default:3
                    ds.setInitialPoolSize(Integer.parseInt(p.getProperty("db.pool.initialPoolSize", "3")));
                   //如果为true,则当连接获取失败时自动关闭数据源,除非重新启动应用程序,所以一般不用,default:false
                    ds.setBreakAfterAcquireFailure(false);
                    //如果设为true那么在取得连接的同时将校验连接的有效性,default:false
                    ds.setTestConnectionOnCheckin(true);
                    
                    // This check is not required, but here to make it clear that nothing changes for people
                    // that don't set this configuration property. It may be safely removed.
                    if(p.getProperty("db.isolation") != null) {
                        ds.setConnectionCustomizerClassName(play.db.DBPlugin.PlayConnectionCustomizer.class.getName());
                    }
                    
                    DB.datasource = ds;
                    url = ds.getJdbcUrl();
                    Connection c = null;
                    try {
                        c = ds.getConnection();
                    } finally {
                        if (c != null) {
                            c.close();
                        }
                    }
                    Logger.info("Connected to %s", ds.getJdbcUrl());

                }

                DB.destroyMethod = p.getProperty("db.destroyMethod", "");

            } catch (Exception e) {
                DB.datasource = null;
                Logger.error(e, "Cannot connected to the database : %s", e.getMessage());
                if (e.getCause() instanceof InterruptedException) {
                    throw new DatabaseException("Cannot connected to the database. Check the configuration.", e);
                }
                throw new DatabaseException("Cannot connected to the database, " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void onApplicationStop() {
        if (Play.mode.isProd()) {
            DB.destroy();
        }
    }

    @Override
    public String getStatus() {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);
        if (DB.datasource == null || !(DB.datasource instanceof ComboPooledDataSource)) {
            out.println("Datasource:");
            out.println("~~~~~~~~~~~");
            out.println("(not yet connected)");
            return sw.toString();
        }
        ComboPooledDataSource datasource = (ComboPooledDataSource) DB.datasource;
        out.println("Datasource:");
        out.println("~~~~~~~~~~~");
        out.println("Jdbc url: " + datasource.getJdbcUrl());
        out.println("Jdbc driver: " + datasource.getDriverClass());
        out.println("Jdbc user: " + datasource.getUser());
	if (Play.mode.isDev()) {
          out.println("Jdbc password: " + datasource.getPassword());
        }
        out.println("Min pool size: " + datasource.getMinPoolSize());
        out.println("Max pool size: " + datasource.getMaxPoolSize());
        out.println("Initial pool size: " + datasource.getInitialPoolSize());
        out.println("Checkout timeout: " + datasource.getCheckoutTimeout());
        return sw.toString();
    }

    @Override
    public void invocationFinally() {
        DB.close();
    }

    private static void check(Properties p, String mode, String property) {
        if (!StringUtils.isEmpty(p.getProperty(property))) {
            Logger.warn("Ignoring " + property + " because running the in " + mode + " db.");
        }
    }

    private static boolean changed() {
        Properties p = Play.configuration;

        if ("mem".equals(p.getProperty("db")) && p.getProperty("db.url") == null) {
            p.put("db.driver", "org.h2.Driver");
            p.put("db.url", "jdbc:h2:mem:play;MODE=MYSQL");
            p.put("db.user", "sa");
            p.put("db.pass", "");
        }

        if ("fs".equals(p.getProperty("db")) && p.getProperty("db.url") == null) {
            p.put("db.driver", "org.h2.Driver");
            p.put("db.url", "jdbc:h2:" + (new File(Play.applicationPath, "db/h2/play").getAbsolutePath()) + ";MODE=MYSQL");
            p.put("db.user", "sa");
            p.put("db.pass", "");
        }
        boolean isJndiDatasource = false;
        String datasourceName = p.getProperty("db", "");
             
        if ((isJndiDatasource || datasourceName.startsWith("java:")) && p.getProperty("db.url") == null) {
            if (DB.datasource == null) {
                return true;
            }
        } else {
            // Internal pool is c3p0, we should call the close() method to destroy it.
            check(p, "internal pool", "db.destroyMethod");

            p.put("db.destroyMethod", "close");
        }

        Matcher m = new jregex.Pattern("^mysql:(//)?(({user}[a-zA-Z0-9_]+)(:({pwd}[^@]+))?@)?(({host}[^/]+)/)?({name}[^\\s]+)$").matcher(p.getProperty("db", ""));
        if (m.matches()) {
            String user = m.group("user");
            String password = m.group("pwd");
            String name = m.group("name");
            String host = m.group("host");
            p.put("db.driver", "com.mysql.jdbc.Driver");
            p.put("db.url", "jdbc:mysql://" + (host == null ? "localhost" : host) + "/" + name + "?useUnicode=yes&characterEncoding=UTF-8&connectionCollation=utf8_general_ci");
            if (user != null) {
                p.put("db.user", user);
            }
            if (password != null) {
                p.put("db.pass", password);
            }
        }
        
        m = new jregex.Pattern("^postgres:(//)?(({user}[a-zA-Z0-9_]+)(:({pwd}[^@]+))?@)?(({host}[^/]+)/)?({name}[^\\s]+)$").matcher(p.getProperty("db", ""));
        if (m.matches()) {
            String user = m.group("user");
            String password = m.group("pwd");
            String name = m.group("name");
            String host = m.group("host");
            p.put("db.driver", "org.postgresql.Driver");
            p.put("db.url", "jdbc:postgresql://" + (host == null ? "localhost" : host) + "/" + name);
            if (user != null) {
                p.put("db.user", user);
            }
            if (password != null) {
                p.put("db.pass", password);
            }
        }

        if(p.getProperty("db.url") != null && p.getProperty("db.url").startsWith("jdbc:h2:mem:")) {
            p.put("db.driver", "org.h2.Driver");
            p.put("db.user", "sa");
            p.put("db.pass", "");
        }

        if ((p.getProperty("db.driver") == null) || (p.getProperty("db.url") == null)) {
            return false;
        }
        
        if (DB.datasource == null) {
            return true;
        } else {
            ComboPooledDataSource ds = (ComboPooledDataSource) DB.datasource;
            if (!p.getProperty("db.driver").equals(ds.getDriverClass())) {
                return true;
            }
            if (!p.getProperty("db.url").equals(ds.getJdbcUrl())) {
                return true;
            }
            if (!p.getProperty("db.user", "").equals(ds.getUser())) {
                return true;
            }
            if (!p.getProperty("db.pass", "").equals(ds.getPassword())) {
                return true;
            }
        }

        if (!p.getProperty("db.destroyMethod", "").equals(DB.destroyMethod)) {
            return true;
        }

        return false;
    }

    /**
     * Needed because DriverManager will not load a driver ouside of the system classloader
     */
    public static class ProxyDriver implements Driver {

        private Driver driver;

        ProxyDriver(Driver d) {
            this.driver = d;
        }
        
        @Override
        public boolean acceptsURL(String u) throws SQLException {
            return this.driver.acceptsURL(u);
        }
        
        @Override
        public Connection connect(String u, Properties p) throws SQLException {
            return this.driver.connect(u, p);
        }
        
        @Override
        public int getMajorVersion() {
            return this.driver.getMajorVersion();
        }
        
        @Override
        public int getMinorVersion() {
            return this.driver.getMinorVersion();
        }
        
        @Override
        public DriverPropertyInfo[] getPropertyInfo(String u, Properties p) throws SQLException {
            return this.driver.getPropertyInfo(u, p);
        }
        
        @Override
        public boolean jdbcCompliant() {
            return this.driver.jdbcCompliant();
        }

		public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
			// TODO Auto-generated method stub
			return null;
		}
    }

    public static class PlayConnectionCustomizer implements ConnectionCustomizer {

        public static Map<String, Integer> isolationLevels;

        static {
            isolationLevels = new HashMap<String, Integer>();
            isolationLevels.put("NONE", Connection.TRANSACTION_NONE);
            isolationLevels.put("READ_UNCOMMITTED", Connection.TRANSACTION_READ_UNCOMMITTED);
            isolationLevels.put("READ_COMMITTED", Connection.TRANSACTION_READ_COMMITTED);
            isolationLevels.put("REPEATABLE_READ", Connection.TRANSACTION_REPEATABLE_READ);
            isolationLevels.put("SERIALIZABLE", Connection.TRANSACTION_SERIALIZABLE);
        }

        public void onAcquire(Connection c, String parentDataSourceIdentityToken) {
            Integer isolation = getIsolationLevel();
            if (isolation != null) {
                try {
                    Logger.trace("Setting connection isolation level to %s", isolation);
                    c.setTransactionIsolation(isolation);
                } catch (SQLException e) {
                    throw new DatabaseException("Failed to set isolation level to " + isolation, e);
                }
            }
        }

        public void onDestroy(Connection c, String parentDataSourceIdentityToken) {}
        public void onCheckOut(Connection c, String parentDataSourceIdentityToken) {}
        public void onCheckIn(Connection c, String parentDataSourceIdentityToken) {}

        /**
         * Get the isolation level from either the isolationLevels map, or by
         * parsing into an int.
         */
        private Integer getIsolationLevel() {
            String isolation = Play.configuration.getProperty("db.isolation");
            if (isolation == null) {
                return null;
            }
            Integer level = isolationLevels.get(isolation);
            if (level != null) {
                return level;
            }

            try {
                return Integer.valueOf(isolation);
            } catch (NumberFormatException e) {
                throw new DatabaseException("Invalid isolation level configuration" + isolation, e);
            }
        }
    }
}
