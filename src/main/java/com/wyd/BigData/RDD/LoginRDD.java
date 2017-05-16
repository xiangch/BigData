package com.wyd.BigData.RDD;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.log4j.helpers.LogLog;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.Optional;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.api.java.function.VoidFunction;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.streaming.flume.SparkFlumeEvent;
import com.wyd.BigData.Global;
import com.wyd.BigData.bean.LoginSumInfo;
import com.wyd.BigData.dao.BaseDao;
import scala.Tuple2;
public class LoginRDD implements Serializable {
    /**
     * 
     */
    private static final long    serialVersionUID = -758442520627154431L;
    static SimpleDateFormat      sf               = new SimpleDateFormat("yyyy_MM_dd");
    private static final Pattern SPACE            = Pattern.compile("\t");

    @SuppressWarnings("serial")
    public static void call(JavaRDD<SparkFlumeEvent> rdd, SparkSession spark) {
        if (rdd.count() == 0) return;        
        LoginRDD ldd = new LoginRDD();
        JavaRDD<SparkFlumeEvent> loingRDD = ldd.filter(rdd);
        LogLog.error("count1=====" + loingRDD.count());
        // 数据源去重
        JavaRDD<Row> loingRowRDD = loingRDD.map(new Function<SparkFlumeEvent, Row>() {
            @Override
            public Row call(SparkFlumeEvent flume) throws Exception {
                String line = new String(flume.event().getBody().array());
                String[] parts = SPACE.split(line);
                return RowFactory.create(Integer.valueOf(parts[2]), Integer.valueOf(parts[3]), Integer.valueOf(parts[5]));
            }
        }).distinct();
        LogLog.error("count2=====" + loingRowRDD.count());
        final String today = sf.format(Calendar.getInstance().getTime());
        // 查询数据库
        BaseDao dao = new BaseDao();
        List<LoginSumInfo> loginInfoFromDBList = dao.getAllLoginSumInfo(today);
        if (loginInfoFromDBList.size() > 0) {
            // 把登陆表里的所有数据查出变成RDD，格式是KEY：playerId,Value:true
            JavaSparkContext jsc = new JavaSparkContext(rdd.context());
            List<Tuple2<Integer, Boolean>> userFromDBList = new ArrayList<>();
            for (LoginSumInfo info : loginInfoFromDBList) {
                userFromDBList.add(new Tuple2<Integer, Boolean>(info.getPlayerId(), true));
            }
            JavaPairRDD<Integer, Boolean> userFromDbRDD = jsc.parallelizePairs(userFromDBList);
            // 把数据来源RDD变成KEY：playerId,Value: 数据本身
            JavaPairRDD<Integer, Row> rdd2Pair = loingRowRDD.mapToPair(new PairFunction<Row, Integer, Row>() {
                @Override
                public Tuple2<Integer, Row> call(Row r) throws Exception {
                    return new Tuple2<Integer, Row>(r.getInt(2), r);
                }
            });
            JavaPairRDD<Integer, Tuple2<Row, Optional<Boolean>>> joined = rdd2Pair.leftOuterJoin(userFromDbRDD);
           
            JavaPairRDD<Integer, Tuple2<Row, Optional<Boolean>>> filtered = joined.filter(new Function<Tuple2<Integer, Tuple2<Row, Optional<Boolean>>>, Boolean>() {
                @Override
                public Boolean call(Tuple2<Integer, Tuple2<Row, Optional<Boolean>>> v1) throws Exception {
                    Optional<Boolean> optional = v1._2._2;
//                    if( optional.isPresent() && optional.get()){
//                        return false;
//                    }
//                    return true;
                    return  !optional.isPresent() || !optional.get();
                }
            });
            LogLog.error("count3=====" + filtered.count());
            
            
            JavaPairRDD<Integer, Row> rdd2filtered = filtered.mapToPair(new PairFunction<Tuple2<Integer, Tuple2<Row, Optional<Boolean>>>, Integer, Row>() {
                @Override
                public Tuple2<Integer, Row> call(Tuple2<Integer, Tuple2<Row, Optional<Boolean>>> t) throws Exception {
                    return new Tuple2<Integer, Row>(t._1, t._2._1);
                }
            });
            rdd2filtered.foreachPartition(new VoidFunction<Iterator<Tuple2<Integer, Row>>>() {
                @Override
                public void call(Iterator<Tuple2<Integer, Row>> t) throws Exception {
                    BaseDao dao = new BaseDao();
                    Row data;
                    List<LoginSumInfo> loginList = new ArrayList<>();
                    while (t.hasNext()) {
                        data = t.next()._2;                        
                        loginList.add(new LoginSumInfo(data.getInt(0), data.getInt(1), data.getInt(1), data.getInt(2)));
                    }
                    dao.saveLoginSumInfoBatch(today, loginList);
                }
            });
        } else {
            loingRowRDD.foreachPartition(new VoidFunction<Iterator<Row>>() {
                @Override
                public void call(Iterator<Row> r) throws Exception {
                    BaseDao dao = new BaseDao();
                    Row data;
                    List<LoginSumInfo> loginList = new ArrayList<>();
                    while (r.hasNext()) {
                        data = r.next();                        
                        loginList.add(new LoginSumInfo(data.getInt(0), data.getInt(1), data.getInt(1), data.getInt(2)));
                    }
                    dao.saveLoginSumInfoBatch(today, loginList);
                }
            });
        }
    }
    // public static void call(JavaRDD<SparkFlumeEvent> rdd, SparkSession spark) {
    // //rdd.foreachPartition(f);
    // if(rdd.count()==0)return;
    // LoginRDD ldd = new LoginRDD();
    // JavaRDD<SparkFlumeEvent> filterRdd = ldd.filter(rdd);
    // JavaRDD<Row> rowRDD = filterRdd.map(new Function<SparkFlumeEvent, Row>() {
    // @Override
    // public Row call(SparkFlumeEvent flume) throws Exception {
    // String line = new String(flume.event().getBody().array());
    // String[] parts = SPACE.split(line);
    // return RowFactory.create(Integer.valueOf(parts[2]), Integer.valueOf(parts[3]),
    // Integer.valueOf(parts[5]));
    // }
    // });
    //
    // if(rowRDD.count()==0)return;
    // StructType schema = ldd.createStruct();
    // // 创建一个DataFrame并去重
    // Dataset<Row> dataFrame = spark.createDataFrame(rowRDD, schema).distinct();
    // dataFrame.createOrReplaceTempView("tmp_login");
    // String tableName = "tab_login_" + sf.format(Calendar.getInstance().getTime());
    // ldd.createTable(spark, tableName);
    // spark.sql("INSERT INTO " + tableName + " select server_id,channel_id,player_id from tmp_login");
    //
    // }

    private void createTable(SparkSession spark, String tableName) {
        spark.sql("CREATE TABLE IF NOT EXISTS " + tableName + " (server_id INT,channel_id INT, player_id INT)");
    }

    /**
     * 昨天登陆玩家数据去重
     */
    public static void distinct() {
        // System.out.println("====================distinct===============");
        LoginRDD ldd = new LoginRDD();
        SparkSession spark = SparkSession.builder().appName("distinct login table").config("hive.metastore.uris", Global.getInstance().config.getString("metastore")).enableHiveSupport().getOrCreate();
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -1);
        String day = sf.format(cal.getTime());
        String tableName = "tab_login_" + day;
        Dataset<Row> dataFrame = spark.sql("select * from tab_task where type=1 and time='" + day + "'");
        // System.out.println("====================dataFrame.count()===============:"+dataFrame.count());
        if (dataFrame.count() == 0) {
            spark.sql("insert into tab_task values(1,'" + day + "')");
            ldd.createTable(spark, tableName);
            spark.sql("INSERT OVERWRITE TABLE " + tableName + " select distinct server_id,channel_id,player_id from " + tableName);
        }
    }

    @SuppressWarnings("serial")
    public JavaRDD<SparkFlumeEvent> filter(JavaRDD<SparkFlumeEvent> rdd) {
        return rdd.filter(new Function<SparkFlumeEvent, Boolean>() {
            @Override
            public Boolean call(SparkFlumeEvent flume) throws Exception {
                String line = new String(flume.event().getBody().array());
                String[] parts = SPACE.split(line);
                return (parts.length >= 2 && "2".equals(parts[0]));
            }
        });
    }

    private StructType createStruct() {
        List<StructField> structFields = new ArrayList<StructField>();
        structFields.add(DataTypes.createStructField("server_id", DataTypes.IntegerType, true));
        structFields.add(DataTypes.createStructField("channel_id", DataTypes.IntegerType, true));
        structFields.add(DataTypes.createStructField("player_id", DataTypes.IntegerType, true));
        return DataTypes.createStructType(structFields);
    }
}
