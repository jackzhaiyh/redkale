/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkale.source;

import com.sun.istack.internal.logging.Logger;
import java.io.Serializable;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.*;
import java.util.logging.Level;
import javax.persistence.*;
import org.redkale.util.*;

/**
 * Entity操作类
 *
 * <p>
 * 详情见: https://redkale.org
 *
 * @author zhangjx
 * @param <T> Entity类的泛型
 */
@SuppressWarnings("unchecked")
public final class EntityInfo<T> {

    //全局静态资源
    private static final ConcurrentHashMap<Class, EntityInfo> entityInfos = new ConcurrentHashMap<>();

    //日志
    private static final Logger logger = Logger.getLogger(EntityInfo.class);

    //Entity类名
    private final Class<T> type;

    //类对应的数据表名, 如果是VirtualEntity 类， 则该字段为null
    final String table;

    //Entity构建器
    private final Creator<T> creator;

    //主键
    final Attribute<T, Serializable> primary;

    //Entity缓存对象
    private final EntityCache<T> cache;

    //key是field的name， 不是sql字段。
    //存放所有与数据库对应的字段， 包括主键
    private final HashMap<String, Attribute<T, Serializable>> attributeMap = new HashMap<>();

    //存放所有与数据库对应的字段， 包括主键
    final Attribute<T, Serializable>[] attributes;

    //key是field的name， value是Column的别名，即数据库表的字段名
    //只有field.name 与 Column.name不同才存放在aliasmap里.
    private final Map<String, String> aliasmap;

    //所有可更新字段，即排除了主键字段和标记为&#064;Column(updatable=false)的字段
    private final Map<String, Attribute<T, Serializable>> updateAttributeMap = new HashMap<>();

    //用于反向LIKE使用
    final String containSQL;

    //用于反向LIKE使用
    final String notcontainSQL;

    //用于判断表不存在的使用, 多个SQLState用;隔开
    final String tablenotexistSqlstates;

    //用于复制表结构使用
    final String tablecopySQL;

    //用于存在table_20160202类似这种分布式表
    final Set<String> tables = new HashSet<>();

    //分表 策略
    final DistributeTableStrategy<T> tableStrategy;

    //根据主键查找单个对象的SQL， 含 ？
    final String querySQL;

    //数据库中所有字段
    private final Attribute<T, Serializable>[] queryAttributes;

    //新增SQL， 含 ？，即排除了自增长主键和标记为&#064;Column(insertable=false)的字段
    private final String insertSQL;

    //数据库中所有可新增字段
    final Attribute<T, Serializable>[] insertAttributes;

    //根据主键更新所有可更新字段的SQL，含 ？
    private final String updateSQL;

    //数据库中所有可更新字段
    final Attribute<T, Serializable>[] updateAttributes;

    //根据主键删除记录的SQL，含 ？
    private final String deleteSQL;

    //日志级别，从LogLevel获取
    private final int logLevel;

    //Flipper.sort转换成以ORDER BY开头SQL的缓存
    private final Map<String, String> sortOrderbySqls = new ConcurrentHashMap<>();

    //是否由数据库生成主键值
    final boolean autoGenerated;

    //是否UUID主键
    final boolean autouuid;

    //所属的DataSource
    final DataSource source;

    //全量数据的加载器
    final BiFunction<DataSource, Class, List> fullloader;
    //------------------------------------------------------------

    /**
     * 加载EntityInfo
     *
     * @param type           Entity类
     * @param cacheForbidden 是否禁用EntityCache
     * @param conf           配置信息, persistence.xml中的property节点值
     * @param source         DataSource,可为null
     * @param fullloader     全量加载器,可为null
     */
    static <T> EntityInfo<T> load(Class<T> clazz, final boolean cacheForbidden, final Properties conf,
        DataSource source, BiFunction<DataSource, Class, List> fullloader) {
        EntityInfo rs = entityInfos.get(clazz);
        if (rs != null) return rs;
        synchronized (entityInfos) {
            rs = entityInfos.get(clazz);
            if (rs == null) {
                rs = new EntityInfo(clazz, cacheForbidden, conf, source, fullloader);
                entityInfos.put(clazz, rs);
                if (rs.cache != null) {
                    if (fullloader == null) throw new IllegalArgumentException(clazz.getName() + " auto loader  is illegal");
                    rs.cache.fullLoad();
                }
            }
            return rs;
        }
    }

    /**
     * 获取Entity类对应的EntityInfo对象
     *
     * @param <T>   泛型
     * @param clazz Entity类
     *
     * @return EntityInfo
     */
    static <T> EntityInfo<T> get(Class<T> clazz) {
        return entityInfos.get(clazz);
    }

    /**
     * 构造函数
     *
     * @param type           Entity类
     * @param cacheForbidden 是否禁用EntityCache
     * @param conf           配置信息, persistence.xml中的property节点值
     * @param source         DataSource,可为null
     * @param fullloader     全量加载器,可为null
     */
    private EntityInfo(Class<T> type, final boolean cacheForbidden,
        Properties conf, DataSource source, BiFunction<DataSource, Class, List> fullloader) {
        this.type = type;
        this.source = source;
        //---------------------------------------------

        LogLevel ll = type.getAnnotation(LogLevel.class);
        this.logLevel = ll == null ? Integer.MIN_VALUE : Level.parse(ll.value()).intValue();
        //---------------------------------------------
        Table t = type.getAnnotation(Table.class);
        if (type.getAnnotation(VirtualEntity.class) != null) {
            this.table = null;
            BiFunction<DataSource, Class, List> loader = null;
            try {
                loader = type.getAnnotation(VirtualEntity.class).loader().newInstance();
            } catch (Exception e) {
                logger.severe(type + " init @VirtualEntity.loader error", e);
            }
            this.fullloader = loader;
        } else {
            this.fullloader = fullloader;
            this.table = (t == null) ? type.getSimpleName().toLowerCase() : (t.catalog().isEmpty()) ? t.name() : (t.catalog() + '.' + (t.name().isEmpty() ? type.getSimpleName().toLowerCase() : t.name()));
        }
        DistributeTable dt = type.getAnnotation(DistributeTable.class);
        DistributeTableStrategy dts = null;
        try {
            dts = (dt == null) ? null : dt.strategy().newInstance();
        } catch (Exception e) {
            logger.severe(type + " init DistributeTableStrategy error", e);
        }
        this.tableStrategy = dts;

        this.creator = Creator.create(type);
        Attribute idAttr0 = null;
        Map<String, String> aliasmap0 = null;
        Class cltmp = type;
        Set<String> fields = new HashSet<>();
        List<Attribute<T, Serializable>> queryattrs = new ArrayList<>();
        List<String> insertcols = new ArrayList<>();
        List<Attribute<T, Serializable>> insertattrs = new ArrayList<>();
        List<String> updatecols = new ArrayList<>();
        List<Attribute<T, Serializable>> updateattrs = new ArrayList<>();
        boolean auto = false;
        boolean uuid = false;

        do {
            for (Field field : cltmp.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (Modifier.isFinal(field.getModifiers())) continue;
                if (field.getAnnotation(Transient.class) != null) continue;
                if (fields.contains(field.getName())) continue;
                final String fieldname = field.getName();
                final Column col = field.getAnnotation(Column.class);
                final String sqlfield = col == null || col.name().isEmpty() ? fieldname : col.name();
                if (!fieldname.equals(sqlfield)) {
                    if (aliasmap0 == null) aliasmap0 = new HashMap<>();
                    aliasmap0.put(fieldname, sqlfield);
                }
                Attribute attr;
                try {
                    attr = Attribute.create(cltmp, field);
                } catch (RuntimeException e) {
                    continue;
                }
                if (field.getAnnotation(javax.persistence.Id.class) != null && idAttr0 == null) {
                    idAttr0 = attr;
                    GeneratedValue gv = field.getAnnotation(GeneratedValue.class);
                    auto = gv != null;
//                    if (gv != null && gv.strategy() != GenerationType.IDENTITY) {
//                        throw new RuntimeException(cltmp.getName() + "'s @ID primary not a GenerationType.IDENTITY");
//                    }
                    if (gv != null && field.getType() == String.class) { //UUID
                        uuid = true;
                        auto = false;
                    }
                    if (!auto) {
                        insertcols.add(sqlfield);
                        insertattrs.add(attr);
                    }
                } else {
                    if (col == null || col.insertable()) {
                        insertcols.add(sqlfield);
                        insertattrs.add(attr);
                    }
                    if (col == null || col.updatable()) {
                        updatecols.add(sqlfield);
                        updateattrs.add(attr);
                        updateAttributeMap.put(fieldname, attr);
                    }
                }
                queryattrs.add(attr);
                fields.add(fieldname);
                attributeMap.put(fieldname, attr);
            }
        } while ((cltmp = cltmp.getSuperclass()) != Object.class);
        this.primary = idAttr0;
        this.aliasmap = aliasmap0;
        this.attributes = attributeMap.values().toArray(new Attribute[attributeMap.size()]);
        this.queryAttributes = queryattrs.toArray(new Attribute[queryattrs.size()]);
        this.insertAttributes = insertattrs.toArray(new Attribute[insertattrs.size()]);
        this.updateAttributes = updateattrs.toArray(new Attribute[updateattrs.size()]);
        if (table != null) {
            StringBuilder insertsb = new StringBuilder();
            StringBuilder insertsb2 = new StringBuilder();
            for (String col : insertcols) {
                if (insertsb.length() > 0) insertsb.append(',');
                insertsb.append(col);
                if (insertsb2.length() > 0) insertsb2.append(',');
                insertsb2.append('?');
            }
            this.insertSQL = "INSERT INTO " + (this.tableStrategy == null ? table : "${newtable}") + "(" + insertsb + ") VALUES(" + insertsb2 + ")";
            StringBuilder updatesb = new StringBuilder();
            for (String col : updatecols) {
                if (updatesb.length() > 0) updatesb.append(", ");
                updatesb.append(col).append(" = ?");
            }
            this.updateSQL = "UPDATE " + (this.tableStrategy == null ? table : "${newtable}") + " SET " + updatesb + " WHERE " + getPrimarySQLColumn(null) + " = ?";
            this.deleteSQL = "DELETE FROM " + (this.tableStrategy == null ? table : "${newtable}") + " WHERE " + getPrimarySQLColumn(null) + " = ?";
            this.querySQL = "SELECT * FROM " + table + " WHERE " + getPrimarySQLColumn(null) + " = ?";
        } else {
            this.insertSQL = null;
            this.updateSQL = null;
            this.deleteSQL = null;
            this.querySQL = null;
        }
        this.autoGenerated = auto;
        this.autouuid = uuid;
        //----------------cache--------------
        Cacheable c = type.getAnnotation(Cacheable.class);
        if (this.table == null || (!cacheForbidden && c != null && c.value())) {
            this.cache = new EntityCache<>(this, c);
        } else {
            this.cache = null;
        }
        if (conf == null) conf = new Properties();
        this.containSQL = conf.getProperty(DataSources.JDBC_CONTAIN_SQLTEMPLATE, "LOCATE(${keystr}, ${column}) > 0");
        this.notcontainSQL = conf.getProperty(DataSources.JDBC_NOTCONTAIN_SQLTEMPLATE, "LOCATE(${keystr}, ${column}) = 0");

        this.tablenotexistSqlstates = ";" + conf.getProperty(DataSources.JDBC_TABLENOTEXIST_SQLSTATES, "42000;42S02") + ";";
        this.tablecopySQL = conf.getProperty(DataSources.JDBC_TABLECOPY_SQLTEMPLATE, "CREATE TABLE ${newtable} LIKE ${oldtable}");
    }

    /**
     * 创建主键值，目前只支持UUID赋值
     *
     * @param src Entity对象
     */
    public void createPrimaryValue(T src) {
        if (autouuid) getPrimary().set(src, Utility.uuid());
    }

    /**
     * 获取Entity缓存器
     *
     * @return EntityCache
     */
    public EntityCache<T> getCache() {
        return cache;
    }

    /**
     * 判断缓存器是否已经全量加载过
     *
     * @return boolean
     */
    public boolean isCacheFullLoaded() {
        return cache != null && cache.isFullLoaded();
    }

    /**
     * 获取Entity构建器
     *
     * @return Creator
     */
    public Creator<T> getCreator() {
        return creator;
    }

    /**
     * 获取Entity类名
     *
     * @return Class
     */
    public Class<T> getType() {
        return type;
    }

    /**
     * 判断Entity是否为虚拟类
     *
     * @return boolean
     */
    public boolean isVirtualEntity() {
        return table == null;
    }

    /**
     * 获取Entity的INSERT SQL
     *
     * @param bean Entity对象
     *
     * @return String
     */
    public String getInsertSQL(T bean) {
        if (this.tableStrategy == null) return insertSQL;
        return insertSQL.replace("${newtable}", getTable(bean));
    }

    /**
     * 获取Entity的UPDATE SQL
     *
     * @param bean Entity对象
     *
     * @return String
     */
    public String getUpdateSQL(T bean) {
        if (this.tableStrategy == null) return updateSQL;
        return updateSQL.replace("${newtable}", getTable(bean));
    }

    /**
     * 获取Entity的DELETE SQL
     *
     * @param bean Entity对象
     *
     * @return String
     */
    public String getDeleteSQL(T bean) {
        if (this.tableStrategy == null) return deleteSQL;
        return deleteSQL.replace("${newtable}", getTable(bean));
    }

    /**
     * 根据主键值获取Entity的表名
     *
     * @param primary Entity主键值
     *
     * @return String
     */
    public String getTable(Serializable primary) {
        if (tableStrategy == null) return table;
        String t = tableStrategy.getTable(table, primary);
        return t == null || t.isEmpty() ? table : t;
    }

    /**
     * 根据过滤条件获取Entity的表名
     *
     * @param node 过滤条件
     *
     * @return String
     */
    public String getTable(FilterNode node) {
        if (tableStrategy == null) return table;
        String t = tableStrategy.getTable(table, node);
        return t == null || t.isEmpty() ? table : t;
    }

    /**
     * 根据Entity对象获取Entity的表名
     *
     * @param bean Entity对象
     *
     * @return String
     */
    public String getTable(T bean) {
        if (tableStrategy == null) return table;
        String t = tableStrategy.getTable(table, bean);
        return t == null || t.isEmpty() ? table : t;
    }

    /**
     * 获取主键字段的Attribute
     *
     * @return Attribute
     */
    public Attribute<T, Serializable> getPrimary() {
        return this.primary;
    }

    /**
     * 遍历数据库表对应的所有字段, 不包含&#64;Transient字段
     *
     * @param action BiConsumer
     */
    public void forEachAttribute(BiConsumer<String, Attribute<T, Serializable>> action) {
        this.attributeMap.forEach(action);
    }

    /**
     * 根据Entity字段名获取字段的Attribute
     *
     * @param fieldname Class字段名
     *
     * @return Attribute
     */
    public Attribute<T, Serializable> getAttribute(String fieldname) {
        if (fieldname == null) return null;
        return this.attributeMap.get(fieldname);
    }

    /**
     * 根据Entity字段名获取可更新字段的Attribute
     *
     * @param fieldname Class字段名
     *
     * @return Attribute
     */
    public Attribute<T, Serializable> getUpdateAttribute(String fieldname) {
        return this.updateAttributeMap.get(fieldname);
    }

    /**
     * 判断Entity类的字段名与表字段名s是否存在不一致的值
     *
     * @return boolean
     */
    public boolean isNoAlias() {
        return this.aliasmap == null;
    }

    /**
     * 根据Flipper获取ORDER BY的SQL语句，不存在Flipper或sort字段返回空字符串
     *
     * @param flipper 翻页对象
     *
     * @return String
     */
    protected String createSQLOrderby(Flipper flipper) {
        if (flipper == null || flipper.getSort() == null) return "";
        final String sort = flipper.getSort();
        if (sort.isEmpty() || sort.indexOf(';') >= 0 || sort.indexOf('\n') >= 0) return "";
        String sql = this.sortOrderbySqls.get(sort);
        if (sql != null) return sql;
        final StringBuilder sb = new StringBuilder();
        sb.append(" ORDER BY ");
        if (isNoAlias()) {
            sb.append(sort);
        } else {
            boolean flag = false;
            for (String item : sort.split(",")) {
                if (item.isEmpty()) continue;
                String[] sub = item.split("\\s+");
                if (flag) sb.append(',');
                if (sub.length < 2 || sub[1].equalsIgnoreCase("ASC")) {
                    sb.append(getSQLColumn("a", sub[0])).append(" ASC");
                } else {
                    sb.append(getSQLColumn("a", sub[0])).append(" DESC");
                }
                flag = true;
            }
        }
        sql = sb.toString();
        this.sortOrderbySqls.put(sort, sql);
        return sql;
    }

    /**
     * 根据field字段名获取数据库对应的字段名
     *
     * @param tabalis   表别名
     * @param fieldname 字段名
     *
     * @return String
     */
    public String getSQLColumn(String tabalis, String fieldname) {
        return this.aliasmap == null ? (tabalis == null ? fieldname : (tabalis + '.' + fieldname))
            : (tabalis == null ? aliasmap.getOrDefault(fieldname, fieldname) : (tabalis + '.' + aliasmap.getOrDefault(fieldname, fieldname)));
    }

    /**
     * 获取主键字段的表字段名
     *
     * @return String
     */
    public String getPrimarySQLColumn() {
        return getSQLColumn(null, this.primary.field());
    }

    /**
     * 获取主键字段的带有表别名的表字段名
     *
     * @param tabalis 表别名
     *
     * @return String
     */
    public String getPrimarySQLColumn(String tabalis) {
        return getSQLColumn(tabalis, this.primary.field());
    }

    /**
     * 拼接UPDATE给字段赋值的SQL片段
     *
     * @param col 表字段名
     * @param cv  ColumnValue
     *
     * @return CharSequence
     */
    protected CharSequence formatSQLValue(String col, final ColumnValue cv) {
        if (cv == null) return null;
        switch (cv.getExpress()) {
            case INC:
                return new StringBuilder().append(col).append(" + ").append(cv.getValue());
            case MUL:
                return new StringBuilder().append(col).append(" * ").append(cv.getValue());
            case AND:
                return new StringBuilder().append(col).append(" & ").append(cv.getValue());
            case ORR:
                return new StringBuilder().append(col).append(" | ").append(cv.getValue());
            case MOV:
                return formatToString(cv.getValue());
        }
        return formatToString(cv.getValue());
    }

    /**
     * 获取所有数据表字段的Attribute, 不包含&#64;Transient字段
     *
     * @return Map
     */
    protected Map<String, Attribute<T, Serializable>> getAttributes() {
        return attributeMap;
    }

    /**
     * 判断日志级别
     *
     * @param l Level
     *
     * @return boolean
     */
    public boolean isLoggable(Level l) {
        return l.intValue() >= this.logLevel;
    }

    /**
     * 将字段值序列化为可SQL的字符串
     *
     * @param value 字段值
     *
     * @return String
     */
    protected String formatToString(Object value) {
        if (value == null) return null;
        if (value instanceof CharSequence) {
            return new StringBuilder().append('\'').append(value.toString().replace("'", "\\'")).append('\'').toString();
        }
        return String.valueOf(value);
    }

    /**
     * 将一行的ResultSet组装成一个Entity对象
     *
     * @param sels 指定字段
     * @param set  ResultSet
     *
     * @return Entity对象
     * @throws SQLException SQLException
     */
    protected T getValue(final SelectColumn sels, final ResultSet set) throws SQLException {
        T obj = creator.create();
        for (Attribute<T, Serializable> attr : queryAttributes) {
            if (sels == null || sels.test(attr.field())) {
                final Class t = attr.type();
                Serializable o;
                if (t == byte[].class) {
                    Blob blob = set.getBlob(this.getSQLColumn(null, attr.field()));
                    if (blob == null) {
                        o = null;
                    } else { //不支持超过2G的数据
                        o = blob.getBytes(1, (int) blob.length());
                    }
                } else {
                    o = (Serializable) set.getObject(this.getSQLColumn(null, attr.field()));
                    if (t.isPrimitive()) {
                        if (o != null) {
                            if (t == int.class) {
                                o = ((Number) o).intValue();
                            } else if (t == long.class) {
                                o = ((Number) o).longValue();
                            } else if (t == short.class) {
                                o = ((Number) o).shortValue();
                            } else if (t == float.class) {
                                o = ((Number) o).floatValue();
                            } else if (t == double.class) {
                                o = ((Number) o).doubleValue();
                            } else if (t == byte.class) {
                                o = ((Number) o).byteValue();
                            } else if (t == char.class) {
                                o = (char) ((Number) o).intValue();
                            }
                        } else if (t == int.class) {
                            o = 0;
                        } else if (t == long.class) {
                            o = 0L;
                        } else if (t == short.class) {
                            o = (short) 0;
                        } else if (t == float.class) {
                            o = 0.0f;
                        } else if (t == double.class) {
                            o = 0.0d;
                        } else if (t == byte.class) {
                            o = (byte) 0;
                        } else if (t == boolean.class) {
                            o = false;
                        } else if (t == char.class) {
                            o = (char) 0;
                        }
                    }
                }
                attr.set(obj, o);
            }
        }
        return obj;
    }
}
