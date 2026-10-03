package com.pis.material;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class CytologyPolicyTest {
 @Test void noInventedInventoryOrFractionalYield(){CytologyPolicy.reserve(99,99);CytologyPolicy.reserve(1,1);for(int n:new int[]{-1,0,2,100})assertThatThrownBy(()->CytologyPolicy.reserve(1,n)).isInstanceOf(IllegalArgumentException.class);}
 @Test void exactReconciliationAndNoFailureOutput(){CytologyPolicy.reconcile(4,2,1,1,2,false);CytologyPolicy.reconcile(4,0,3,1,0,true);for(int[] q:new int[][]{{4,3,1,1,2},{4,1,1,2,2},{4,4,0,0,0},{99,99,0,0,21},{0,0,0,0,0}})assertThatThrownBy(()->CytologyPolicy.reconcile(q[0],q[1],q[2],q[3],q[4],false)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->CytologyPolicy.reconcile(1,1,0,0,1,true)).isInstanceOf(IllegalArgumentException.class);}
}
