package server.util;

/**
 * 数値計算アルゴリズムを提供するサービスのインターフェース
 */
public interface NumericalSolverService {
    
    /**
     * 線形方程式を解く
     * 
     * @param pressCoeff 圧力係数行列
     * @param dataAll 右辺ベクトル
     * @param output 解ベクトル
     * @param node ノード数
     * @param maxIter 最大反復回数
     * @param eps 収束判定閾値
     * @return 収束した場合は反復回数、収束しなかった場合は負の値
     */
    int solve(double[][] pressCoeff, double[] dataAll, double[] output, int node, int maxIter, double eps);
    
    /**
     * ベクトルの内積を計算する
     * 
     * @param a ベクトルa
     * @param b ベクトルb
     * @return 内積
     */
    double dot(double[] a, double[] b);
    
    /**
     * 行列とベクトルの積を計算する
     * 
     * @param A 行列
     * @param x ベクトル
     * @param result 結果を格納するベクトル
     */
    void matVecMult(double[][] A, double[] x, double[] result);
    
    /**
     * アルゴリズム名を取得する
     * 
     * @return アルゴリズム名
     */
    String getAlgorithmName();
}
