/*
 * Copyright (c) 2001, Zoltan Farkas All Rights Reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package org.spf4j.concurrent.jdbc;

import com.google.common.annotations.Beta;
import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.CheckReturnValue;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spf4j.base.HandlerNano;
import org.spf4j.base.IntMath;
import org.spf4j.base.MutableHolder;
import org.spf4j.concurrent.DefaultExecutor;
import org.spf4j.jdbc.JdbcTemplate;
import org.spf4j.jmx.JmxExport;
import org.spf4j.jmx.Registry;

/**
 * A jdbc table based semaphore implementation. Similar with a semaphore implemented with zookeeper, we rely on
 * heartbeats to detect dead members. If you have a zookeeper instance accessible you should probably use a semaphore
 * implemented with it... If you are already connecting to a database. this should be a reliable and low overhead (no
 * calls from DBA) implementation. (at leat that is my goal) Using a crappy database will give you crappy results.
 *
 * There are 3 tables involved:
 *
 * SEMAPHORES - keep track of available and total permits by semaphore. PERMITS_BY_OWNER - keeps track of all permits by
 * owner. HEARTBEATS - keeps heartbeats by owner to detect - dead owners.
 *
 * All table names and columns are customizable to adapt this implementation to different naming conventions.
 *
 *
 * @author zoly
 */
@SuppressFBWarnings({"SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING", "NP_LOAD_OF_KNOWN_NULL_VALUE"})
@Beta
public final class JdbcSemaphore implements AutoCloseable, Semaphore {

  private static final Logger LOG = LoggerFactory.getLogger(JdbcSemaphore.class);

  private final JdbcTemplate jdbc;

  private final String availablePermitsSql;

  private final String ownedPermitsSql;

  private final String totalPermitsSql;

  private final String reducePermitsSql;

  private final String increasePermitsSql;

  private final String updatePermitsSql;

  private final String acquireSql;

  private final String acquireByOwnerSql;

  private final String releaseSql;

  private final String releaseByOwnerSql;

  private final String deleteDeadOwnerRecordsSql;

  private final String getDeadOwnerPermitsSql;

  private final String deleteDeadOwerRecordSql;

  private final int jdbcTimeoutSeconds;

  private final IntMath.XorShift32 rnd;

  private final String semName;

  private final SemaphoreTablesDesc semTableDesc;

  private final JdbcHeartBeat heartBeat;

  private volatile boolean isHealthy;

  private Error heartBeatFailure;

  private static final Interner<String> INTERNER = Interners.newStrongInterner();

  private final int acquirePollMillis;

  private final JdbcHeartBeat.LifecycleHook failureHook;

  private int ownedReservations;

  /**
   * @param dataSource  the jdbc data source with the Semaphores table. Please be sensible, no "test on borrow" pools.
   * @param semaphoreName  number of initial permits, if semaphore already exists the existing nr of permits is kept.
   * @param nrPermits  the number of initial permits.
   */
  public JdbcSemaphore(final DataSource dataSource, final String semaphoreName, final int nrPermits)
          throws InterruptedException {
    this(dataSource, semaphoreName, nrPermits, false);
  }

  /**
   * create a JDBC Semaphore. create one instance / process.
   *
   * @param dataSource  the data source to use for sync.
   * @param semaphoreName  the semaphore name.
   * @param nrPermits  number of initial permits.
   * @param strict  if true, if semaphore already exists and the total permits is different that param nrPermits an
   * IllegalArgumentException will be thrown.
   */
  public JdbcSemaphore(final DataSource dataSource, final String semaphoreName,
          final int nrPermits, final boolean strict) throws InterruptedException {
    this(dataSource, SemaphoreTablesDesc.DEFAULT, semaphoreName, nrPermits, 10, strict);
  }

  public JdbcSemaphore(final DataSource dataSource, final SemaphoreTablesDesc semTableDesc,
          final String semaphoreName, final int nrPermits, final int jdbcTimeoutSeconds,
          final boolean strictReservations) throws InterruptedException {
    this(dataSource, semTableDesc, semaphoreName, nrPermits, jdbcTimeoutSeconds, strictReservations,
            Integer.getInteger("spf4j.jdbc.semaphore.defaultMaxPollIntervalMillis", 1000));
  }


  @SuppressFBWarnings({"CBX_CUSTOM_BUILT_XML", "STT_TOSTRING_STORED_IN_FIELD"}) // no sql builder (yet)
  public JdbcSemaphore(final DataSource dataSource, final SemaphoreTablesDesc semTableDesc,
          final String semaphoreName, final int nrPermits, final int jdbcTimeoutSeconds,
          final boolean strictReservations, final int acquirePollMillis) throws InterruptedException {
    if (nrPermits < 0) {
      throw new IllegalArgumentException("Permits must be positive and not " + nrPermits);
    }
    this.acquirePollMillis = acquirePollMillis;
    this.semName = INTERNER.intern(semaphoreName);
    this.jdbcTimeoutSeconds = jdbcTimeoutSeconds;
    this.jdbc = new JdbcTemplate(dataSource);
    this.semTableDesc = semTableDesc;
    this.rnd = new IntMath.XorShift32();
    this.isHealthy = true;
    this.ownedReservations = 0;
    this.failureHook = new JdbcHeartBeat.LifecycleHook() {
      @Override
      public void onError(final Error error) {
        heartBeatFailure = error;
        isHealthy = false;
      }

      @Override
      public void onClose() {
          close();
      }
    };
    this.heartBeat = JdbcHeartBeat.getHeartBeatAndSubscribe(dataSource,
            semTableDesc.getHeartBeatTableDesc(), failureHook);
    final String semaphoreTableName = semTableDesc.getSemaphoreTableName();
    String availablePermitsColumn = semTableDesc.getAvailablePermitsColumn();
    String lastModifiedByColumn = semTableDesc.getLastModifiedByColumn();
    String lastModifiedAtColumn = semTableDesc.getLastModifiedAtColumn();
    String ownerColumn = semTableDesc.getOwnerColumn();
    String semaphoreNameColumn = semTableDesc.getSemNameColumn();
    String totalPermitsColumn = semTableDesc.getTotalPermitsColumn();
    String ownerPermitsColumn = semTableDesc.getOwnerReservationsColumn();
    String permitsByOwnerTableName = semTableDesc.getPermitsByOwnerTableName();
    HeartBeatTableDesc hbTableDesc = heartBeat.getHbTableDesc();
    String heartBeatTableName = hbTableDesc.getTableName();
    String heartBeatOwnerColumn = hbTableDesc.getOwnerColumn();
    String currentTimeMillisFunc = hbTableDesc.getCurrentTimeMillisFunc();

    this.reducePermitsSql = "UPDATE " + semaphoreTableName + " SET "
            + totalPermitsColumn + " = " + totalPermitsColumn + " - ?, "
            + availablePermitsColumn + " = " + availablePermitsColumn + " - ? , "
            + lastModifiedByColumn + " = ?, " + lastModifiedAtColumn + " = " + currentTimeMillisFunc + " WHERE "
            + semaphoreNameColumn + " = ? AND "
            + totalPermitsColumn + " >= ?";

    this.increasePermitsSql = "UPDATE " + semaphoreTableName + " SET "
            + totalPermitsColumn + " = " + totalPermitsColumn + " + ?, "
            + availablePermitsColumn + " = " + availablePermitsColumn + " + ?, "
            + lastModifiedByColumn + " = ?, " + lastModifiedAtColumn + " = " + currentTimeMillisFunc + " WHERE "
            + semaphoreNameColumn + " = ? ";

    this.updatePermitsSql = "UPDATE " + semaphoreTableName + " SET "
            + totalPermitsColumn + " =  ?, "
            + availablePermitsColumn + " =  " + availablePermitsColumn + " + ? - " + totalPermitsColumn + ','
            + lastModifiedByColumn + " = ?, " + lastModifiedAtColumn + " = " + currentTimeMillisFunc + " WHERE "
            + semaphoreNameColumn + " = ?";

    this.acquireSql = "UPDATE " + semaphoreTableName + " SET "
            + availablePermitsColumn + " = " + availablePermitsColumn + " - ?, "
            + lastModifiedByColumn + " = ?, " + lastModifiedAtColumn + " = " + currentTimeMillisFunc + " WHERE "
            + semaphoreNameColumn + " = ? AND "
            + availablePermitsColumn + " >= ?";
    this.acquireByOwnerSql = "UPDATE " + permitsByOwnerTableName
            + " SET " + ownerPermitsColumn + " = " + ownerPermitsColumn + " + ?, "
            + lastModifiedAtColumn + " = " + currentTimeMillisFunc + " WHERE "
            + ownerColumn + " = ? AND " + semaphoreNameColumn + " = ?";

    this.releaseSql = "UPDATE " + semaphoreTableName + " SET "
            + availablePermitsColumn + " = CASE WHEN "
            + availablePermitsColumn + " + ? > " + totalPermitsColumn
            + " THEN " + totalPermitsColumn + " ELSE " + availablePermitsColumn + " + ? END, "
            + lastModifiedByColumn + " = ?, " + lastModifiedAtColumn + " = " + currentTimeMillisFunc + " WHERE "
            + semaphoreNameColumn + " = ?";

    this.releaseByOwnerSql = "UPDATE " + permitsByOwnerTableName
            + " SET " + ownerPermitsColumn + " = " + ownerPermitsColumn
            + " - ?, " + lastModifiedAtColumn + " = " + currentTimeMillisFunc + " WHERE "
            + ownerColumn + " = ? AND " + semaphoreNameColumn + " = ? and " + ownerPermitsColumn + " >= ?";

    this.availablePermitsSql = "SELECT " + semTableDesc.getAvailablePermitsColumn()
            + ',' + totalPermitsColumn + " FROM " + semTableDesc.getSemaphoreTableName()
            + " WHERE " + semTableDesc.getSemNameColumn() + " = ?";

    this.totalPermitsSql = "SELECT " + totalPermitsColumn
            + ',' + totalPermitsColumn + " FROM " + semTableDesc.getSemaphoreTableName()
            + " WHERE " + semTableDesc.getSemNameColumn() + " = ?";

    this.ownedPermitsSql = "SELECT " + ownerPermitsColumn + " FROM "
            + permitsByOwnerTableName + " WHERE "
            + ownerColumn + " = ? AND " + semaphoreNameColumn + " = ?";

    this.deleteDeadOwnerRecordsSql = "DELETE FROM " + permitsByOwnerTableName + " RO "
            + "WHERE RO." + semaphoreNameColumn + " = ? AND " + ownerPermitsColumn + " = 0 AND "
            + "NOT EXISTS (select H." + heartBeatOwnerColumn + " from " + heartBeatTableName
            + " H where H." + heartBeatOwnerColumn + " = RO." + ownerColumn + ')';

    this.getDeadOwnerPermitsSql = "SELECT " + ownerColumn + ", " + ownerPermitsColumn
            + " FROM " + permitsByOwnerTableName + " RO "
            + "WHERE RO." + semaphoreNameColumn + " = ? AND  " + ownerPermitsColumn + " > 0 AND "
            + "NOT EXISTS (select H." + heartBeatOwnerColumn + " from " + heartBeatTableName
            + " H where H." + heartBeatOwnerColumn + " = RO." + ownerColumn
            + ") ORDER BY " + ownerColumn + ',' + ownerPermitsColumn;

    this.deleteDeadOwerRecordSql = "DELETE FROM " + permitsByOwnerTableName + " WHERE "
            + ownerColumn + " = ? AND " + semaphoreNameColumn + " = ? AND "
            + ownerPermitsColumn + " = ?";

    try {
      createLockRowIfNotPresent(strictReservations, nrPermits);
    } catch (SQLIntegrityConstraintViolationException ex) {
      try {
        // RACE condition while creating the row, will retry to validate if everything is OK.
        createLockRowIfNotPresent(strictReservations, nrPermits);
      } catch (SQLException ex1) {
        RuntimeException rx = new RuntimeException(ex1);
        rx.addSuppressed(ex);
        throw rx;
      }
    } catch (SQLException ex) {
      throw new RuntimeException(ex);
    }
    try {
      createOwnerRow();
    } catch (SQLException ex) {
      throw new RuntimeException(ex);
    }
  }

  public void registerJmx() {
    Registry.export(JdbcSemaphore.class.getName(), semName, this);
  }

  public void unregisterJmx() {
    Registry.unregister(JdbcSemaphore.class.getName(), semName);
  }

  private void validate() {
    if (!isHealthy) {
      throw new IllegalStateException("Heartbeats failed! semaphore broken " + this, heartBeatFailure);
    }
  }

  void createLockRowIfNotPresent(final boolean strictReservations, final int nrPermits)
          throws SQLException, InterruptedException {
    final String lastModifiedByColumn = semTableDesc.getLastModifiedByColumn();
    final String lastModifiedAtColumn = semTableDesc.getLastModifiedAtColumn();
    final String tableName = semTableDesc.getSemaphoreTableName();
    final String semNameColumn = semTableDesc.getSemNameColumn();
    final String availableReservationsColumn = semTableDesc.getAvailablePermitsColumn();
    final String maxReservationsColumn = semTableDesc.getTotalPermitsColumn();

    jdbc.transactOnConnection((final Connection conn, final long deadlineNanos) -> {
      try (final PreparedStatement stmt = conn.prepareStatement("SELECT " + availableReservationsColumn
              + ',' + maxReservationsColumn + " FROM " + tableName
              + " WHERE " + semNameColumn + " = ?")) {
        stmt.setNString(1, semName);
        stmt.setQueryTimeout((int) TimeUnit.NANOSECONDS.toSeconds(deadlineNanos - System.nanoTime()));
        try (final ResultSet rs = stmt.executeQuery()) {
          if (!rs.next()) {
            try (final PreparedStatement insert = conn.prepareStatement("insert into " + tableName
                    + " (" + semNameColumn + ',' + availableReservationsColumn + ',' + maxReservationsColumn
                    + ',' + lastModifiedByColumn + ',' + lastModifiedAtColumn + ") VALUES (?, ?, ?, ?, "
                    + heartBeat.getHbTableDesc().getCurrentTimeMillisFunc() + ')')) {
              insert.setNString(1, semName);
              insert.setInt(2, nrPermits);
              insert.setInt(3, nrPermits);
              insert.setNString(4, org.spf4j.base.Runtime.PROCESS_ID);
              insert.setQueryTimeout((int) TimeUnit.NANOSECONDS.toSeconds(deadlineNanos - System.nanoTime()));
              insert.executeUpdate();
            }
          } else if (strictReservations) { // there is a record already. for now blow up if different nr reservations.
            int existingMaxReservations = rs.getInt(2);
            if (existingMaxReservations != nrPermits) {
              throw new IllegalArgumentException("Semaphore " + semName + " max reservations count different "
                      + existingMaxReservations + " != " + nrPermits + " use different semaphore");
            }
            if (rs.next()) {
              throw new IllegalStateException("Cannot have mutiple semaphores with the same name " + semName);
            }
          } else if (rs.next()) {
            throw new IllegalStateException("Cannot have mutiple semaphores with the same name " + semName);
          }
        }
      }
      return null;
    }, jdbcTimeoutSeconds, TimeUnit.SECONDS);
  }

  void createOwnerRow()
          throws SQLException, InterruptedException {

    jdbc.transactOnConnection((final Connection conn, final long deadlineNanos) -> {

      try (final PreparedStatement insert = conn.prepareStatement("insert into "
              + semTableDesc.getPermitsByOwnerTableName()
              + " (" + semTableDesc.getSemNameColumn() + ',' + semTableDesc.getOwnerColumn() + ','
              + semTableDesc.getOwnerReservationsColumn() + ','
              + semTableDesc.getLastModifiedAtColumn() + ") VALUES (?, ?, ?, "
              + heartBeat.getHbTableDesc().getCurrentTimeMillisFunc() + ")")) {
        insert.setNString(1, this.semName);
        insert.setNString(2, org.spf4j.base.Runtime.PROCESS_ID);
        insert.setInt(3, 0);
        insert.setQueryTimeout((int) TimeUnit.NANOSECONDS.toSeconds(deadlineNanos - System.nanoTime()));
        insert.executeUpdate();
      }
      return null;
    }, jdbcTimeoutSeconds, TimeUnit.SECONDS);
  }

  public static int nanosToSeconds(final long nanos) {
    long seconds = TimeUnit.NANOSECONDS.toSeconds(nanos);
    if (seconds > Integer.MAX_VALUE) {
      return Integer.MAX_VALUE;
    } else {
      return (int) seconds;
    }
  }

  @Override
  public void acquire(final long timeout, final TimeUnit unit)
          throws InterruptedException, TimeoutException {
    acquire(1, timeout, unit);
  }

  @Override
  public void acquire(final int nrPermits, final long timeout, final TimeUnit unit)
          throws InterruptedException, TimeoutException {
    if (!tryAcquire(nrPermits, timeout, unit)) {
      throw new TimeoutException("Cannot acquire timeout after " + timeout + " " + unit);
    }
  }

  @Override
  public boolean tryAcquire(final long timeout, final TimeUnit unit)
          throws InterruptedException {
    return tryAcquire(1, timeout, unit);
  }

  @SuppressFBWarnings("UW_UNCOND_WAIT")
  @CheckReturnValue
  @Override
  public boolean tryAcquire(final int nrPermits, final long timeout, final TimeUnit unit)
          throws InterruptedException {
    if (nrPermits < 1) {
      throw new IllegalArgumentException("You should try to acquire something! not " + nrPermits);
    }
    synchronized (semName) {
      long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
      boolean acquired = false;
      final MutableHolder<Boolean> beat = MutableHolder.of(Boolean.FALSE);
      do {
        validate();
        try {
          acquired = jdbc.transactOnConnection(new HandlerNano<Connection, Boolean, SQLException>() {
            @Override
            public Boolean handle(final Connection conn, final long deadlineNanos) throws SQLException {
              try (PreparedStatement stmt = conn.prepareStatement(acquireSql)) {
                stmt.setQueryTimeout(nanosToSeconds(deadlineNanos - System.nanoTime()));
                stmt.setInt(1, nrPermits);
                stmt.setNString(2, org.spf4j.base.Runtime.PROCESS_ID);
                stmt.setNString(3, semName);
                stmt.setInt(4, nrPermits);
                int rowsUpdated = stmt.executeUpdate();
                Boolean acquired;
                if (rowsUpdated == 1) {
                  try (PreparedStatement ostmt = conn.prepareStatement(acquireByOwnerSql)) {
                    ostmt.setInt(1, nrPermits);
                    ostmt.setNString(2, org.spf4j.base.Runtime.PROCESS_ID);
                    ostmt.setNString(3, semName);
                    ostmt.setQueryTimeout(nanosToSeconds(deadlineNanos - System.nanoTime()));
                    int nrUpdated = ostmt.executeUpdate();
                    if (nrUpdated != 1) {
                      throw new IllegalStateException("Updated " + nrUpdated + " is incorrect for " + ostmt);
                    }
                  }
                  acquired = Boolean.TRUE;
                } else {
                  if (rowsUpdated > 1) {
                    throw new IllegalStateException("Too many rows updated! when trying to acquire " + nrPermits);
                  }
                  acquired = Boolean.FALSE;
                }
                if (deadlineNanos - System.nanoTime() > heartBeat.getBeatDurationNanos()) {
                  // do a heartbeat if have time, and if it makes sense.
                  beat.setValue(heartBeat.tryBeat(conn, deadlineNanos));
                }
                return acquired;
              }
            }
          }, timeout, unit);
        } catch (SQLException ex) {
          throw new RuntimeException(ex);
        }
        if (beat.getValue()) { // we did a heartbeat as part of the acquisition.
          heartBeat.updateLastRun(System.currentTimeMillis());
        }
        if (!acquired) {
          Future<Integer> fut = DefaultExecutor.INSTANCE.submit(new Callable<Integer>() {
            @Override
            public Integer call() throws Exception {
              return removeDeadHeartBeatAndNotOwnerRows(60);
            }
          });
          try {
            fut.get(deadlineNanos - System.nanoTime(), TimeUnit.NANOSECONDS);
          } catch (TimeoutException ex) {
            //removing dead entries did not finish in time, but continues in the background.
            break;
          } catch (ExecutionException ex) {
            throw new RuntimeException(ex);
          }
          if (releaseDeadOwnerPermits(nrPermits) <= 0) { //wait of we did not find anything dead to release.
            long wtimeMilis = Math.min(TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()),
                    Math.abs(rnd.nextInt()) % acquirePollMillis);
            if (wtimeMilis > 0) {
              semName.wait(wtimeMilis);
            } else {
              break;
            }
          }

        }
      } while (!acquired && deadlineNanos > System.nanoTime());
      if (acquired) {
        ownedReservations += nrPermits;
      }

      return acquired;
    }
  }

  @Override
  public void release() {
    release(1);
  }

  @Override
  @SuppressFBWarnings("EXS_EXCEPTION_SOFTENING_NO_CHECKED")
  public void release(final int nrReservations) {
    synchronized (semName) {
      try {
        jdbc.transactOnConnectionNonInterrupt(new HandlerNano<Connection, Void, SQLException>() {
          @Override
          public Void handle(final Connection conn, final long deadlineNanos) throws SQLException {
            releaseReservations(conn, deadlineNanos, nrReservations);
            try (PreparedStatement ostmt = conn.prepareStatement(releaseByOwnerSql)) {
              ostmt.setInt(1, nrReservations);
              ostmt.setNString(2, org.spf4j.base.Runtime.PROCESS_ID);
              ostmt.setNString(3, semName);
              ostmt.setInt(4, nrReservations);
              ostmt.setQueryTimeout(nanosToSeconds(deadlineNanos - System.nanoTime()));
              int nrUpdated = ostmt.executeUpdate();
              if (nrUpdated != 1) {
                throw new IllegalStateException("Trying to release more than you own! " + ostmt);
              }
            }
            return null;
          }
        }, jdbcTimeoutSeconds, TimeUnit.SECONDS);
      } catch (SQLException ex) {
        throw new RuntimeException(ex);
      }
      ownedReservations -= nrReservations;
      if (ownedReservations < 0) {
        throw new IllegalStateException("Should not be trying to release more than you acquired!" + nrReservations);
      }
      semName.notifyAll();
    }
  }

  public void releaseAll() {
    synchronized (semName) {
      release(ownedReservations);
    }
  }

  private void releaseReservations(final Connection conn, final long deadlineNanos, final int nrReservations)
          throws SQLException {
    try (PreparedStatement stmt = conn.prepareStatement(releaseSql)) {
      stmt.setQueryTimeout(nanosToSeconds(deadlineNanos - System.nanoTime()));
      stmt.setInt(1, nrReservations);
      stmt.setInt(2, nrReservations);
      stmt.setNString(3, org.spf4j.base.Runtime.PROCESS_ID);
      stmt.setNString(4, semName);
      stmt.executeUpdate(); // Since a release might or might not update a row.
    }
  }

  @JmxExport(description = "Get the available semaphore permits")
  public int availablePermits() throws SQLException, InterruptedException {
    return jdbc.transactOnConnection((final Connection conn, final long deadlineNanos) -> {
      try (final PreparedStatement stmt = conn.prepareStatement(availablePermitsSql)) {
        stmt.setNString(1, semName);
        stmt.setQueryTimeout((int) TimeUnit.NANOSECONDS.toSeconds(deadlineNanos - System.nanoTime()));
        try (final ResultSet rs = stmt.executeQuery()) {
          if (!rs.next()) {
            throw new IllegalStateException();
          } else {
            int result = rs.getInt(1);
            if (rs.next()) {
              throw new IllegalStateException();
            }
            return result;
          }
        }
      }
    }, jdbcTimeoutSeconds, TimeUnit.SECONDS);
  }

  @JmxExport(description = "get the number of permits owned by this process")
  public int permitsOwned() throws SQLException, InterruptedException {
    return jdbc.transactOnConnection((final Connection conn, final long deadlineNanos) -> {
      try (final PreparedStatement stmt = conn.prepareStatement(ownedPermitsSql)) {
        stmt.setNString(1, org.spf4j.base.Runtime.PROCESS_ID);
        stmt.setNString(2, semName);
        stmt.setQueryTimeout((int) TimeUnit.NANOSECONDS.toSeconds(deadlineNanos - System.nanoTime()));
        try (final ResultSet rs = stmt.executeQuery()) {
          if (!rs.next()) {
            throw new IllegalStateException();
          } else {
            int result = rs.getInt(1);
            if (rs.next()) {
              throw new IllegalStateException();
            }
            return result;
          }
        }
      }
    }, jdbcTimeoutSeconds, TimeUnit.SECONDS);
  }

  @JmxExport(description = "Get the total permits this semaphore can hand out")
  public int totalPermits() throws SQLException, InterruptedException {
    return jdbc.transactOnConnection((final Connection conn, final long deadlineNanos) -> {
      try (final PreparedStatement stmt = conn.prepareStatement(totalPermitsSql)) {
        stmt.setNString(1, semName);
        stmt.setQueryTimeout((int) TimeUnit.NANOSECONDS.toSeconds(deadlineNanos - System.nanoTime()));
        try (final ResultSet rs = stmt.executeQuery()) {
          if (!rs.next()) {
            throw new IllegalStateException();
          } else {
            return rs.getInt(1);
          }
        }
      }
    }, jdbcTimeoutSeconds, TimeUnit.SECONDS);
  }

  @JmxExport(description = "get a list of all dead owners which hold permits")
  public List<OwnerPermits> getDeadOwnerPermits(final int wishPermits) throws SQLException, InterruptedException {
    return jdbc.transactOnConnection((final Connection conn, final long deadlineNanos) -> {
      return getDeadOwnerPermits(conn, deadlineNanos, wishPermits);
    }, jdbcTimeoutSeconds, TimeUnit.SECONDS);
  }

  List<OwnerPermits> getDeadOwnerPermits(final Connection conn, final long deadlineNanos, final int wishPermits)
          throws SQLException {
    List<OwnerPermits> result = new ArrayList<>();
    try (final PreparedStatement stmt = conn.prepareStatement(getDeadOwnerPermitsSql)) {
      stmt.setNString(1, semName);
      stmt.setQueryTimeout((int) TimeUnit.NANOSECONDS.toSeconds(deadlineNanos - System.nanoTime()));
      try (final ResultSet rs = stmt.executeQuery()) {
        int nrPermits = 0;
        while (rs.next()) {
          OwnerPermits ownerPermit = new OwnerPermits(rs.getNString(1), rs.getInt(2));
          result.add(ownerPermit);
          nrPermits += ownerPermit.getNrPermits();
          if (nrPermits >= wishPermits) {
            break;
          }
        }
      }
    }
    return result;
  }

  /**
   * Attempts to release permits for this semaphore owned by dead owners.
   *
   * @param wishPermits - How many permits we would like to get released.
   * @return - the number of permits we actually released.
   * @throws SQLException - something went wrong with the db.
   * @throws InterruptedException - thrown if thread is interrupted.
   */
  @JmxExport(description = "release dead owner permits")
  @CheckReturnValue
  public int releaseDeadOwnerPermits(@JmxExport(value = "wishPermits",
          description = "how many we whish to release") final int wishPermits)
          throws InterruptedException {
    try {
      return jdbc.transactOnConnection((final Connection conn, final long deadlineNanos) -> {
        List<OwnerPermits> deadOwnerPermits = getDeadOwnerPermits(conn, deadlineNanos, wishPermits);
        int released = 0;
        for (OwnerPermits permit : deadOwnerPermits) {
          try (final PreparedStatement stmt = conn.prepareStatement(deleteDeadOwerRecordSql)) {
            String owner = permit.getOwner();
            stmt.setNString(1, owner);
            stmt.setNString(2, semName);
            int nrPermits = permit.getNrPermits();
            stmt.setInt(3, nrPermits);
            stmt.setQueryTimeout((int) TimeUnit.NANOSECONDS.toSeconds(deadlineNanos - System.nanoTime()));
            if (stmt.executeUpdate() == 1) { // I can release! if not somebody else is doing it.
              released += nrPermits;
              releaseReservations(conn, deadlineNanos, nrPermits);
              LOG.warn("Released {} reservations from dead owner {}", nrPermits, owner);
            }
          }
        }
        return released;
      }, jdbcTimeoutSeconds, TimeUnit.SECONDS);
    } catch (SQLException ex) {
      throw new RuntimeException(ex);
    }
  }

  @JmxExport(description = "Change the total available permits to the provided number")
  public void updatePermits(final int nrPermits) throws SQLException, InterruptedException {
    if (nrPermits < 0) {
      throw new IllegalArgumentException("Permits must be positive and not " + nrPermits);
    }
    jdbc.transactOnConnection(new HandlerNano<Connection, Void, SQLException>() {
      @Override
      public Void handle(final Connection conn, final long deadlineNanos) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(updatePermitsSql)) {
          stmt.setQueryTimeout(nanosToSeconds(deadlineNanos - System.nanoTime()));
          stmt.setInt(1, nrPermits);
          stmt.setInt(2, nrPermits);
          stmt.setNString(3, org.spf4j.base.Runtime.PROCESS_ID);
          stmt.setNString(4, semName);
          int rowsUpdated = stmt.executeUpdate();
          if (rowsUpdated != 1) {
            throw new IllegalArgumentException("Cannot reduce nr total permits by " + nrPermits);
          }
        }
        return null;
      }
    }, jdbcTimeoutSeconds, TimeUnit.SECONDS);
  }

  @JmxExport(description = "Reduce the total available permits by the provided number")
  public void reducePermits(final int nrPermits) throws SQLException, InterruptedException {
    jdbc.transactOnConnection(new HandlerNano<Connection, Void, SQLException>() {
      @Override
      public Void handle(final Connection conn, final long deadlineNanos) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(reducePermitsSql)) {
          stmt.setQueryTimeout(nanosToSeconds(deadlineNanos - System.nanoTime()));
          stmt.setInt(1, nrPermits);
          stmt.setInt(2, nrPermits);
          stmt.setNString(3, org.spf4j.base.Runtime.PROCESS_ID);
          stmt.setNString(4, semName);
          stmt.setInt(5, nrPermits);
          int rowsUpdated = stmt.executeUpdate();
          if (rowsUpdated != 1) {
            throw new IllegalArgumentException("Cannot reduce nr total permits by " + nrPermits);
          }
        }
        return null;
      }
    }, jdbcTimeoutSeconds, TimeUnit.SECONDS);
  }

  @JmxExport(description = "Increase the total available permits by the provided number")
  public void increasePermits(final int nrPermits) throws SQLException, InterruptedException {
    jdbc.transactOnConnection(new HandlerNano<Connection, Void, SQLException>() {
      @Override
      public Void handle(final Connection conn, final long deadlineNanos) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(increasePermitsSql)) {
          stmt.setQueryTimeout(nanosToSeconds(deadlineNanos - System.nanoTime()));
          stmt.setInt(1, nrPermits);
          stmt.setInt(2, nrPermits);
          stmt.setNString(3, org.spf4j.base.Runtime.PROCESS_ID);
          stmt.setNString(4, semName);
          int rowsUpdated = stmt.executeUpdate();
          if (rowsUpdated != 1) {
            throw new IllegalArgumentException("Cannot reduce nr total permits by " + nrPermits);
          }
        }
        return null;
      }
    }, jdbcTimeoutSeconds, TimeUnit.SECONDS);
  }

  public int removeDeadHeartBeatAndNotOwnerRows(final int timeoutSeconds) throws SQLException, InterruptedException {
    return jdbc.transactOnConnection(new HandlerNano<Connection, Integer, SQLException>() {
      @Override
      public Integer handle(final Connection conn, final long deadlineNanos) throws SQLException {
        return removeDeadHeartBeatAndNotOwnerRows(conn, deadlineNanos);
      }
    }, timeoutSeconds, TimeUnit.SECONDS);
  }

  int removeDeadHeartBeatAndNotOwnerRows(final Connection conn, final long deadlineNanos) throws SQLException {
    int removedDeadHeartBeatRows = this.heartBeat.removeDeadHeartBeatRows(conn, deadlineNanos);
    if (removedDeadHeartBeatRows > 0) {
      return removeDeadNotOwnedRowsOnly(conn, deadlineNanos);
    } else {
      return 0;
    }
  }

  int removeDeadNotOwnedRowsOnly(final Connection conn, final long deadlineNanos) throws SQLException {
    try (final PreparedStatement stmt = conn.prepareStatement(deleteDeadOwnerRecordsSql)) {
      stmt.setNString(1, semName);
      stmt.setQueryTimeout((int) TimeUnit.NANOSECONDS.toSeconds(deadlineNanos - System.nanoTime()));
      return stmt.executeUpdate();
    }
  }

  @Override
  public String toString() {
    return "JdbcSemaphore{" + "jdbc=" + jdbc
            + ", jdbcTimeoutSeconds=" + jdbcTimeoutSeconds + ", semName=" + semName + '}';
  }

  @Override
  public void close() {
    releaseAll();
    unregisterJmx();
    this.heartBeat.removeLifecycleHook(failureHook);
    isHealthy = false;
  }

  @JmxExport
  public int getJdbcTimeoutSeconds() {
    return jdbcTimeoutSeconds;
  }

  @JmxExport
  public boolean isIsHealthy() {
    return isHealthy;
  }


}
