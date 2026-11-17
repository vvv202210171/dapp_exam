package aegis.dao;

import aegis.domain.Models.AppError;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 进程内唯一的 SQLite 连接。建表、事务和参数绑定都从这里走，DAO 不再各自打开库。
 */
public final class Database implements AutoCloseable {
    private final Connection db;

    /**
     * 打开数据库，开启外键和 WAL，并创建表。
     *
     * @param path SQLite 文件路径
     */
    public Database(Path path) {
        try {
            Files.createDirectories(path.getParent() == null ? Path.of(".") : path.getParent());
            String url = "jdbc:sqlite:" + path.toAbsolutePath().toString().replace('\\', '/');
            db = DriverManager.getConnection(url);
            exec("PRAGMA busy_timeout = 5000");
            exec("PRAGMA foreign_keys = ON");
            exec("PRAGMA journal_mode = WAL");
            for (String stmt : SCHEMA.split(";")) {
                if (!stmt.isBlank()) exec(stmt);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 在一个事务里执行。业务异常和运行时异常都会回滚。
     *
     * @param work 事务内的操作，可以再调用本连接上的查询和更新
     * @param <T> 返回值类型
     * @return 操作结果
     */
    public synchronized <T> T tx(Supplier<T> work) {
        try {
            db.setAutoCommit(false);
            T value = work.get();
            db.commit();
            return value;
        } catch (AppError e) {
            rollback();
            throw e;
        } catch (RuntimeException e) {
            rollback();
            throw e;
        } catch (Exception e) {
            rollback();
            throw new RuntimeException(e);
        } finally {
            autocommit();
        }
    }

    /**
     * 执行插入或更新。
     *
     * @param sql SQL，占位符用问号
     * @param args 按顺序绑定的参数，Long 和 Integer 按数字绑定，其余按字符串
     */
    public synchronized void update(String sql, Object... args) {
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            bind(ps, args);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 查询多行。
     *
     * @param sql SQL
     * @param mapper 把当前行转成对象
     * @param args 绑定参数
     * @param <T> 行类型
     * @return 结果列表，没有行时为空列表
     */
    public synchronized <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(mapper.map(rs));
                return out;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 关闭连接。
     */
    public void close() {
        try { db.close(); } catch (SQLException ignored) {}
    }

    /**
     * 执行一条不带参数的 SQL，用于建表和 PRAGMA。
     *
     * @param sql SQL
     */
    private void exec(String sql) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement(sql)) { ps.execute(); }
    }

    /** 回滚当前事务，失败时忽略。 */
    private void rollback() { try { db.rollback(); } catch (SQLException ignored) {} }

    /** 恢复自动提交，失败时忽略。 */
    private void autocommit() { try { db.setAutoCommit(true); } catch (SQLException ignored) {} }

    /**
     * 按位置绑定参数。
     *
     * @param ps 已准备的语句
     * @param args 参数
     */
    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof Long n) ps.setLong(i + 1, n);
            else if (a instanceof Integer n) ps.setInt(i + 1, n);
            else ps.setString(i + 1, a == null ? "" : a.toString());
        }
    }

    @FunctionalInterface
    /**
     * 把结果集当前行映射成对象。
     *
     * @param <T> 行类型
     */
    public interface RowMapper<T> {
        /**
         * @param rs 已经定位到当前行的结果集
         * @return 这一行对应的对象
         */
        T map(ResultSet rs) throws SQLException;
    }

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS accounts (id TEXT PRIMARY KEY, label TEXT NOT NULL, created_at INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS addresses (
              account_id TEXT NOT NULL, chain TEXT NOT NULL, address TEXT NOT NULL, derivation_path TEXT NOT NULL,
              PRIMARY KEY (account_id, chain), UNIQUE (chain, address));
            CREATE TABLE IF NOT EXISTS transfers (
              id TEXT PRIMARY KEY, account_id TEXT NOT NULL, chain TEXT NOT NULL, asset TEXT NOT NULL,
              from_addr TEXT NOT NULL, to_addr TEXT NOT NULL, amount TEXT NOT NULL, fee TEXT NOT NULL DEFAULT '',
              tx_hash TEXT NOT NULL DEFAULT '', signed_payload TEXT NOT NULL DEFAULT '', status TEXT NOT NULL,
              detail TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
            CREATE INDEX IF NOT EXISTS idx_transfers_account ON transfers(account_id, created_at DESC);
            CREATE TABLE IF NOT EXISTS cards (
              id TEXT PRIMARY KEY, account_id TEXT NOT NULL, kind TEXT NOT NULL, status TEXT NOT NULL, last4 TEXT NOT NULL,
              pan_enc TEXT NOT NULL, cvv_enc TEXT NOT NULL, expiry TEXT NOT NULL, holder TEXT NOT NULL, currency TEXT NOT NULL,
              available INTEGER NOT NULL, created_at INTEGER NOT NULL);
            CREATE INDEX IF NOT EXISTS idx_cards_account ON cards(account_id, created_at DESC);
            CREATE TABLE IF NOT EXISTS ledger_entries (
              id TEXT PRIMARY KEY, card_id TEXT NOT NULL, kind TEXT NOT NULL, amount INTEGER NOT NULL, balance_after INTEGER NOT NULL,
              merchant TEXT NOT NULL DEFAULT '', mcc TEXT NOT NULL DEFAULT '', ref TEXT NOT NULL, note TEXT NOT NULL DEFAULT '',
              created_at INTEGER NOT NULL, UNIQUE (card_id, ref));
            CREATE INDEX IF NOT EXISTS idx_ledger_card ON ledger_entries(card_id, created_at);
            CREATE TABLE IF NOT EXISTS settlements (
              entry_id TEXT PRIMARY KEY, account_id TEXT NOT NULL, tx_hash TEXT NOT NULL, units TEXT NOT NULL, created_at INTEGER NOT NULL);
            """;
}
