package server.util;

/**
 * 設定パラメータを管理するクラス
 */
public class ConfigurationManager {
    // 定数
    private static final double DEFAULT_INF = 10000.0;
    private static final double DEFAULT_NEG = -1.0;
    private static final double DEFAULT_GAMMA = 1.01;
    private static final double DEFAULT_DELTA_TIME = 0.01;
    private static final int DEFAULT_PLOT = 1;
    private static final int DEFAULT_PLOT_2 = 20;
    private static final double DEFAULT_INIT_THICKNESS = 0.5;
    private static final double DEFAULT_INIT_LENGTH = 1.0;
    private static final double DEFAULT_INIT_RATE = 100.0;
    private static final double DEFAULT_THRESHOLD_1 = 0.5;
    private static final double DEFAULT_THRESHOLD_2 = 2.0;
    private static final double DEFAULT_COEFFICIENT_TANH = 1.0;
    
    // インスタンス変数
    private double inf = DEFAULT_INF;
    private double neg = DEFAULT_NEG;
    private double gamma = DEFAULT_GAMMA;
    private double deltaTime = DEFAULT_DELTA_TIME;
    private int plot = DEFAULT_PLOT;
    private int plot2 = DEFAULT_PLOT_2;
    private double initThickness = DEFAULT_INIT_THICKNESS;
    private double initLength = DEFAULT_INIT_LENGTH;
    private double initRate = DEFAULT_INIT_RATE;
    private double threshold1 = DEFAULT_THRESHOLD_1;
    private double threshold2 = DEFAULT_THRESHOLD_2;
    private double coefficientTanh = DEFAULT_COEFFICIENT_TANH;
    
    // シングルトンインスタンス
    private static ConfigurationManager instance;
    
    /**
     * プライベートコンストラクタ
     */
    private ConfigurationManager() {
        // シングルトンパターン
    }
    
    /**
     * インスタンスを取得する
     * 
     * @return ConfigurationManagerのインスタンス
     */
    public static synchronized ConfigurationManager getInstance() {
        if (instance == null) {
            instance = new ConfigurationManager();
        }
        return instance;
    }
    
    /**
     * 設定を初期値にリセットする
     */
    public void resetToDefaults() {
        inf = DEFAULT_INF;
        neg = DEFAULT_NEG;
        gamma = DEFAULT_GAMMA;
        deltaTime = DEFAULT_DELTA_TIME;
        plot = DEFAULT_PLOT;
        plot2 = DEFAULT_PLOT_2;
        initThickness = DEFAULT_INIT_THICKNESS;
        initLength = DEFAULT_INIT_LENGTH;
        initRate = DEFAULT_INIT_RATE;
        threshold1 = DEFAULT_THRESHOLD_1;
        threshold2 = DEFAULT_THRESHOLD_2;
        coefficientTanh = DEFAULT_COEFFICIENT_TANH;
    }
    
    // ゲッターとセッター
    public double getInf() {
        return inf;
    }
    
    public void setInf(double inf) {
        this.inf = inf;
    }
    
    public double getNeg() {
        return neg;
    }
    
    public void setNeg(double neg) {
        this.neg = neg;
    }
    
    public double getGamma() {
        return gamma;
    }
    
    public void setGamma(double gamma) {
        this.gamma = gamma;
    }
    
    public double getDeltaTime() {
        return deltaTime;
    }
    
    public void setDeltaTime(double deltaTime) {
        this.deltaTime = deltaTime;
    }
    
    public int getPlot() {
        return plot;
    }
    
    public void setPlot(int plot) {
        this.plot = plot;
    }
    
    public int getPlot2() {
        return plot2;
    }
    
    public void setPlot2(int plot2) {
        this.plot2 = plot2;
    }
    
    public double getInitThickness() {
        return initThickness;
    }
    
    public void setInitThickness(double initThickness) {
        this.initThickness = initThickness;
    }
    
    public double getInitLength() {
        return initLength;
    }
    
    public void setInitLength(double initLength) {
        this.initLength = initLength;
    }
    
    public double getInitRate() {
        return initRate;
    }
    
    public void setInitRate(double initRate) {
        this.initRate = initRate;
    }
    
    public double getThreshold1() {
        return threshold1;
    }
    
    public void setThreshold1(double threshold1) {
        this.threshold1 = threshold1;
    }
    
    public double getThreshold2() {
        return threshold2;
    }
    
    public void setThreshold2(double threshold2) {
        this.threshold2 = threshold2;
    }
    
    public double getCoefficientTanh() {
        return coefficientTanh;
    }
    
    public void setCoefficientTanh(double coefficientTanh) {
        this.coefficientTanh = coefficientTanh;
    }
    
    /**
     * 設定を文字列として取得する
     * 
     * @return 設定の文字列表現
     */
    @Override
    public String toString() {
        return "ConfigurationManager{" +
                "inf=" + inf +
                ", neg=" + neg +
                ", gamma=" + gamma +
                ", deltaTime=" + deltaTime +
                ", plot=" + plot +
                ", plot2=" + plot2 +
                ", initThickness=" + initThickness +
                ", initLength=" + initLength +
                ", initRate=" + initRate +
                ", threshold1=" + threshold1 +
                ", threshold2=" + threshold2 +
                ", coefficientTanh=" + coefficientTanh +
                '}';
    }
}
