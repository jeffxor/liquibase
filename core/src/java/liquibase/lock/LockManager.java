package liquibase.lock;

import liquibase.database.Database;
import liquibase.exception.JDBCException;
import liquibase.exception.LockException;
import liquibase.sql.visitor.SqlVisitor;
import liquibase.statement.*;
import liquibase.util.NetUtil;
import liquibase.util.log.LogFactory;

import java.net.InetAddress;
import java.sql.Timestamp;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LockManager {

    private Database database;

    private boolean hasChangeLogLock = false;

    private long changeLogLockWaitTime = 1000 * 60 * 5;  //default to 5 mins
    private long changeLogLocRecheckTime = 1000 * 10;  //default to every 10 seconds

    private static Map<Database, LockManager> instances = new ConcurrentHashMap<Database, LockManager>();

    private LockManager(Database database) {
        this.database = database;
    }

    public static LockManager getInstance(Database database) {
        if (!instances.containsKey(database)) {
            instances.put(database, new LockManager(database));
        }
        return instances.get(database);
    }

    public void setChangeLogLockWaitTime(long changeLogLockWaitTime) {
        this.changeLogLockWaitTime = changeLogLockWaitTime;
    }

    public void setChangeLogLockRecheckTime(long changeLogLocRecheckTime) {
        this.changeLogLocRecheckTime = changeLogLocRecheckTime;
    }

    public boolean hasChangeLogLock() {
        return hasChangeLogLock;
    }

    public void waitForLock() throws LockException {

        boolean locked = false;
        long timeToGiveUp = new Date().getTime() + changeLogLockWaitTime;
        while (!locked && new Date().getTime() < timeToGiveUp) {
            locked = acquireLock();
            if (!locked) {
                System.out.println("Waiting for changelog lock....");
                try {
                    Thread.sleep(changeLogLocRecheckTime);
                } catch (InterruptedException e) {
                    ;
                }
            }
        }

        if (!locked) {
            DatabaseChangeLogLock[] locks = listLocks();
            String lockedBy;
            if (locks.length > 0) {
                DatabaseChangeLogLock lock = locks[0];
                lockedBy = lock.getLockedBy() + " since " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(lock.getLockGranted());
            } else {
                lockedBy = "UNKNOWN";
            }
            throw new LockException("Could not acquire change log lock.  Currently locked by " + lockedBy);
        }
    }

    public boolean acquireLock() throws LockException {
        if (hasChangeLogLock) {
            return true;
        }

        try {
            database.checkDatabaseChangeLogLockTable();

            Boolean locked;
            try {
                locked = (Boolean) database.queryForObject(new SelectFromDatabaseChangeLogLockStatement("locked"), Boolean.class, new ArrayList<SqlVisitor>());
            } catch (JDBCException e) {
                if (!database.getExecutor().executesStatements()) {
                    //expected
                    locked = false;
                } else {
                    throw new LockException("Error checking database lock status", e);
                }
            }
            if (locked) {
                return false;
            } else {

                database.comment("Lock Database");
                int rowsUpdated = database.update(new LockDatabaseChangeLogStatement(), new ArrayList<SqlVisitor>());
                if (rowsUpdated != 1) {
                    if (!database.getExecutor().executesStatements()) {
                        //expected
                    } else {
                        throw new LockException("Did not update change log lock correctly");
                    }
                }
                database.commit();
                LogFactory.getLogger().info("Successfully acquired change log lock");

                hasChangeLogLock = true;
                return true;
            }
        } catch (Exception e) {
            throw new LockException(e);
        }

    }

    public void releaseLock() throws LockException {
        try {
            if (database.doesChangeLogLockTableExist()) {

                database.comment("Release Database Lock");
                int updatedRows = database.update(new UnlockDatabaseChangeLogStatement(), new ArrayList<SqlVisitor>());
                if (updatedRows != 1) {
                    if (database.getExecutor().executesStatements()) {
                        throw new LockException("Did not update change log lock correctly.\n\n" + updatedRows + "rows were updated instead of the expected 1 row.");
                    }
                }
                database.commit();
                hasChangeLogLock = false;

                instances.remove(this.database);

                LogFactory.getLogger().info("Successfully released change log lock");
            }
        } catch (Exception e) {
            throw new LockException(e);
        }
    }

    public DatabaseChangeLogLock[] listLocks() throws LockException {
        try {
            if (!database.doesChangeLogLockTableExist()) {
                return new DatabaseChangeLogLock[0];
            }

            List<DatabaseChangeLogLock> allLocks = new ArrayList<DatabaseChangeLogLock>();
            SqlStatement sqlStatement = new SelectFromDatabaseChangeLogLockStatement("ID", "LOCKED", "LOCKGRANTED", "LOCKEDBY");
            List<Map> rows = database.queryForList(sqlStatement, new ArrayList<SqlVisitor>());
            for (Map columnMap : rows) {
                Object lockedValue = columnMap.get("LOCKED");
                Boolean locked;
                if (lockedValue instanceof Number) {
                    locked = ((Number) lockedValue).intValue() == 1;
                } else {
                    locked = (Boolean) lockedValue;
                }
                if (locked != null && locked) {
                    allLocks.add(new DatabaseChangeLogLock(((Number) columnMap.get("ID")).intValue(), (Date) columnMap.get("LOCKGRANTED"), (String) columnMap.get("LOCKEDBY")));
                }
            }
            return allLocks.toArray(new DatabaseChangeLogLock[allLocks.size()]);
        } catch (Exception e) {
            throw new LockException(e);
        }
    }

    /**
     * Releases whatever locks are on the database change log table
     */
    public void forceReleaseLock() throws LockException, JDBCException {
        database.checkDatabaseChangeLogLockTable();

        releaseLock();
    }

    /**
     * Clears information the lock handler knows about the tables.  Should only be called by LiquiBase internal calls
     */
    public void reset() {
        hasChangeLogLock = false;
    }
}