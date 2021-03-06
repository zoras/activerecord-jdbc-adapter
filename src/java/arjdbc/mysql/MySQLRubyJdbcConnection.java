/***** BEGIN LICENSE BLOCK *****
 * Copyright (c) 2012-2013 Karol Bucek <self@kares.org>
 * Copyright (c) 2006-2010 Nick Sieger <nick@nicksieger.com>
 * Copyright (c) 2006-2007 Ola Bini <ola.bini@gmail.com>
 * Copyright (c) 2008-2009 Thomas E Enebo <enebo@acm.org>
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 ***** END LICENSE BLOCK *****/
package arjdbc.mysql;

import arjdbc.jdbc.RubyJdbcConnection;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.joda.time.DateTime;
import org.jruby.Ruby;
import org.jruby.RubyClass;
import org.jruby.RubyInteger;
import org.jruby.RubyString;
import org.jruby.RubyTime;
import org.jruby.anno.JRubyMethod;
import org.jruby.exceptions.RaiseException;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.TypeConverter;

/**
 *
 * @author nicksieger
 */
public class MySQLRubyJdbcConnection extends RubyJdbcConnection {
    private static final long serialVersionUID = -8842614212147138733L;

    protected MySQLRubyJdbcConnection(Ruby runtime, RubyClass metaClass) {
        super(runtime, metaClass);
    }

    private static ObjectAllocator MYSQL_JDBCCONNECTION_ALLOCATOR = new ObjectAllocator() {
        public IRubyObject allocate(Ruby runtime, RubyClass klass) {
            return new MySQLRubyJdbcConnection(runtime, klass);
        }
    };

    public static RubyClass createMySQLJdbcConnectionClass(Ruby runtime, RubyClass jdbcConnection) {
        RubyClass clazz = getConnectionAdapters(runtime).
            defineClassUnder("MySQLJdbcConnection", jdbcConnection, MYSQL_JDBCCONNECTION_ALLOCATOR);
        clazz.defineAnnotatedMethods(MySQLRubyJdbcConnection.class);
        return clazz;
    }

    @JRubyMethod
    public IRubyObject query(final ThreadContext context, final IRubyObject sql) throws SQLException {
        final String query = sql.convertToString().getUnicodeValue(); // sql
        return executeUpdate(context, query, false);
    }

    @Override
    protected boolean doExecute(final Statement statement, final String query) throws SQLException {
        return statement.execute(query, Statement.RETURN_GENERATED_KEYS);
    }

    @Override
    protected IRubyObject jdbcToRuby(final ThreadContext context, final Ruby runtime,
        final int column, final int type, final ResultSet resultSet) throws SQLException {
        if ( type == Types.BIT ) {
            final int value = resultSet.getInt(column);
            return resultSet.wasNull() ? runtime.getNil() : runtime.newFixnum(value);
        }
        return super.jdbcToRuby(context, runtime, column, type, resultSet);
    }

    @Override // can not use statement.setTimestamp( int, Timestamp, Calendar )
    protected void setTimestampParameter(ThreadContext context, Connection connection, PreparedStatement statement,
        int index, IRubyObject value, IRubyObject column, int type) throws SQLException {
        value = callMethod(context, "time_in_default_timezone", value);
        TypeConverter.checkType(context, value, context.runtime.getTime());
        setTimestamp(statement, index, (RubyTime) value, type);
    }

    @Override
    protected void setTimeParameter(ThreadContext context, Connection connection, PreparedStatement statement,
        int index, IRubyObject value, IRubyObject column, int type) throws SQLException {
        setTimestampParameter(context, connection, statement, index, value, column, type);
    }

    // FIXME: we should detect adapter and not do this timezone offset calculation is it is jdbc version 6+.
    private void setTimestamp(PreparedStatement statement, int index, RubyTime value, int type) throws SQLException {
        DateTime dateTime = value.getDateTime();
        int offset = TimeZone.getDefault().getOffset(dateTime.getMillis()); // JDBC <6.x ignores time zone info (we adjust manually).
        Timestamp timestamp = new Timestamp(dateTime.getMillis() - offset);

        // 1942-11-30T01:02:03.123_456
        if (type != Types.DATE && value.getNSec() >= 0) timestamp.setNanos((int) (timestamp.getNanos() + value.getNSec()));

        statement.setTimestamp(index, timestamp);
    }

    // FIXME: I think we can unify this back to main adapter code since previous conflict involved not using
    // the raw string return type and not the extra formatting logic.
    @Override
    protected IRubyObject timeToRuby(ThreadContext context, Ruby runtime, ResultSet resultSet, int column) throws SQLException {
        Time value = resultSet.getTime(column);

        if (value == null) return resultSet.wasNull() ? runtime.getNil() : runtime.newString();

        String strValue = value.toString();

        // If time is column type but that time had a precision which included
        // nanoseconds we used timestamp to save the data.  Since this is conditional
        // we grab data a second time as a timestamp to look for nsecs.
        Timestamp nsecTimeHack = resultSet.getTimestamp(column);
        if (nsecTimeHack.getNanos() != 0) {
            strValue = String.format("%s.%09d", strValue, nsecTimeHack.getNanos());
        }

        return RubyString.newUnicodeString(runtime,strValue);
    }

    @Override
    protected final boolean isConnectionValid(final ThreadContext context, final Connection connection) {
        if ( connection == null ) return false;
        Statement statement = null;
        try {
            final RubyString aliveSQL = getAliveSQL(context);
            final RubyInteger aliveTimeout = getAliveTimeout(context);
            if ( aliveSQL != null ) {
                // expect a SELECT/CALL SQL statement
                statement = createStatement(context, connection);
                if (aliveTimeout != null) {
                    statement.setQueryTimeout((int) aliveTimeout.getLongValue()); // 0 - no timeout
                }
                statement.execute( aliveSQL.toString() );
                return true; // connection alive
            }
            else { // alive_sql nil (or not a statement we can execute)
                return connection.isValid(aliveTimeout == null ? 0 : (int) aliveTimeout.getLongValue()); // since JDBC 4.0
                // ... isValid(0) (default) means no timeout applied
            }
        }
        catch (Exception e) {
            debugMessage(context, "connection considered broken due: " + e.toString());
            return false;
        }
        catch (AbstractMethodError e) { // non-JDBC 4.0 driver
            warn( context,
                "WARN: driver does not support checking if connection isValid()" +
                " please make sure you're using a JDBC 4.0 compilant driver or" +
                " set `connection_alive_sql: ...` in your database configuration" );
            debugStackTrace(context, e);
            throw e;
        }
        finally { close(statement); }
    }

    @Override
    protected String caseConvertIdentifierForRails(Connection connection, String value) throws SQLException {
        return value; // MySQL does not storesUpperCaseIdentifiers() :
    }

    @Override
    protected Connection newConnection() throws RaiseException, SQLException {
        final Connection connection = super.newConnection();
        if ( doStopCleanupThread() ) shutdownCleanupThread();
        if ( doKillCancelTimer(connection) ) killCancelTimer(connection);
        return connection;
    }

    private static Boolean stopCleanupThread;
    static {
        final String stopThread = System.getProperty("arjdbc.mysql.stop_cleanup_thread");
        if ( stopThread != null ) stopCleanupThread = Boolean.parseBoolean(stopThread);
    }

    private static boolean doStopCleanupThread() throws SQLException {
        // TODO when refactoring default behavior to "stop" consider not doing so for JNDI
        return stopCleanupThread != null && stopCleanupThread.booleanValue();
    }

    private static boolean cleanupThreadShutdown;

    private static void shutdownCleanupThread() {
        if ( cleanupThreadShutdown ) return;
        try {
            Class threadClass = Class.forName("com.mysql.jdbc.AbandonedConnectionCleanupThread");
            threadClass.getMethod("shutdown").invoke(null);
        }
        catch (ClassNotFoundException e) {
            debugMessage("INFO: missing MySQL JDBC cleanup thread: " + e);
        }
        catch (NoSuchMethodException e) {
            debugMessage( e.toString() );
        }
        catch (IllegalAccessException e) {
            debugMessage( e.toString() );
        }
        catch (InvocationTargetException e) {
            debugMessage( e.getTargetException().toString() );
        }
        catch (SecurityException e) {
            debugMessage( e.toString() );
        }
        finally { cleanupThreadShutdown = true; }
    }

    private static Boolean killCancelTimer;
    static {
        final String killTimer = System.getProperty("arjdbc.mysql.kill_cancel_timer");
        if ( killTimer != null ) killCancelTimer = Boolean.parseBoolean(killTimer);
    }

    private static boolean doKillCancelTimer(final Connection connection) throws SQLException {
        if ( killCancelTimer == null ) {
            synchronized (MySQLRubyJdbcConnection.class) {
                final String version = connection.getMetaData().getDriverVersion();
                if ( killCancelTimer == null ) {
                    String regex = "mysql\\-connector\\-java-(\\d)\\.(\\d)\\.(\\d+)";
                    Matcher match = Pattern.compile(regex).matcher(version);
                    if ( match.find() ) {
                        final int major = Integer.parseInt( match.group(1) );
                        final int minor = Integer.parseInt( match.group(2) );
                        if ( major < 5 || ( major == 5 && minor <= 1 ) ) {
                            final int patch = Integer.parseInt( match.group(3) );
                            killCancelTimer = patch < 11;
                        }
                    }
                    else {
                        killCancelTimer = Boolean.FALSE;
                    }
                }
            }
        }
        return killCancelTimer;
    }

    /**
     * HACK HACK HACK See http://bugs.mysql.com/bug.php?id=36565
     * MySQL's statement cancel timer can cause memory leaks, so cancel it
     * if we loaded MySQL classes from the same class-loader as JRuby
     *
     * NOTE: MySQL Connector/J 5.1.11 (2010-01-21) fixed the issue !
     */
    private void killCancelTimer(final Connection connection) {
        if (connection.getClass().getClassLoader() == getRuntime().getJRubyClassLoader()) {
            Field field = cancelTimerField();
            if ( field != null ) {
                java.util.Timer timer = null;
                try {
                    Connection unwrap = connection.unwrap(Connection.class);
                    // when failover is used (LoadBalancedMySQLConnection)
                    // we'll end up with a proxy returned not the real thing :
                    if ( Proxy.isProxyClass(unwrap.getClass()) ) return;
                    // connection likely: com.mysql.jdbc.JDBC4Connection
                    // or (for 3.0) super class: com.mysql.jdbc.ConnectionImpl
                    timer = (java.util.Timer) field.get( unwrap );
                }
                catch (SQLException e) {
                    debugMessage( e.toString() );
                }
                catch (IllegalAccessException e) {
                    debugMessage( e.toString() );
                }
                if ( timer != null ) timer.cancel();
            }
        }
    }

    private static Field cancelTimer = null;
    private static boolean cancelTimerChecked = false;

    private static Field cancelTimerField() {
        if ( cancelTimerChecked ) return cancelTimer;
        try {
            Class klass = Class.forName("com.mysql.jdbc.ConnectionImpl");
            Field field = klass.getDeclaredField("cancelTimer");
            field.setAccessible(true);
            synchronized(MySQLRubyJdbcConnection.class) {
                if ( cancelTimer == null ) cancelTimer = field;
            }
        }
        catch (ClassNotFoundException e) {
            debugMessage("INFO: missing MySQL JDBC connection impl: " + e);
        }
        catch (NoSuchFieldException e) {
            debugMessage("INFO: MySQL's cancel timer seems to have changed: " + e);
        }
        catch (SecurityException e) {
            debugMessage( e.toString() );
        }
        finally { cancelTimerChecked = true; }
        return cancelTimer;
    }

}
