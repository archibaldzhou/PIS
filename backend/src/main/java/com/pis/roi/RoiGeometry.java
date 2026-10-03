package com.pis.roi;
import java.util.*;
/** Image pixels only; independent anisotropic calibration, never screen pixels. */
public final class RoiGeometry {
 public record Point(double x,double y) {}
 public record Measure(double length,double area,String unit,Long calibrationVersion) {}
 private RoiGeometry(){}
 public static void validate(String kind,List<Point> p,int width,int height){
  if(p==null||p.size()>32||!Set.of("POINT","RECTANGLE","POLYGON").contains(kind))bad();
  if((kind.equals("POINT")&&p.size()!=1)||(kind.equals("RECTANGLE")&&p.size()!=2)||(kind.equals("POLYGON")&&p.size()<3))bad();
  for(var q:p)if(q==null||!Double.isFinite(q.x)||!Double.isFinite(q.y)||q.x<0||q.y<0||q.x>width||q.y>height)bad();
  if(new HashSet<>(p).size()!=p.size())bad();
  if(kind.equals("RECTANGLE")&&(Math.abs(p.get(0).x-p.get(1).x)<1e-6||Math.abs(p.get(0).y-p.get(1).y)<1e-6))bad();
  if(kind.equals("POLYGON")){if(Math.abs(area(p))<1e-6)bad();for(int i=0;i<p.size();i++)for(int j=i+1;j<p.size();j++){if(j==i+1||(i==0&&j==p.size()-1))continue;if(intersects(p.get(i),p.get((i+1)%p.size()),p.get(j),p.get((j+1)%p.size())))bad();}}
 }
 private static double cross(Point a,Point b,Point c){return (b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x);}
 private static boolean on(Point a,Point b,Point c){return Math.abs(cross(a,b,c))<1e-9&&c.x>=Math.min(a.x,b.x)-1e-9&&c.x<=Math.max(a.x,b.x)+1e-9&&c.y>=Math.min(a.y,b.y)-1e-9&&c.y<=Math.max(a.y,b.y)+1e-9;}
 private static boolean intersects(Point a,Point b,Point c,Point d){double ab=cross(a,b,c),ad=cross(a,b,d),ca=cross(c,d,a),cb=cross(c,d,b);return (ab*ad<0&&ca*cb<0)||on(a,b,c)||on(a,b,d)||on(c,d,a)||on(c,d,b);}
 private static double area(List<Point> p){double a=0;for(int i=0;i<p.size();i++){var x=p.get(i);var y=p.get((i+1)%p.size());a+=x.x*y.y-y.x*x.y;}return a/2;}
 public static Measure measure(String kind,List<Point> p,Double x,Double y,Long version){
  if((x==null)!=(y==null)||(x==null)!=(version==null))bad();if(x!=null&&(!Double.isFinite(x)||!Double.isFinite(y)||x<=0||y<=0||x>100||y>100))bad();
  double sx=x==null?1:x,sy=y==null?1:y;var q=p.stream().map(a->new Point(a.x*sx,a.y*sy)).toList();double length=0,area=0;
  if(kind.equals("RECTANGLE")){double w=Math.abs(q.get(1).x-q.get(0).x),h=Math.abs(q.get(1).y-q.get(0).y);length=2*(w+h);area=w*h;}
  if(kind.equals("POLYGON")){area=Math.abs(area(q));for(int i=0;i<q.size();i++){var a=q.get(i);var b=q.get((i+1)%q.size());length+=Math.hypot(a.x-b.x,a.y-b.y);}}
  return new Measure(length,area,x==null?"px":"SYNTHETIC_um",version);
 }
 private static void bad(){throw new IllegalArgumentException("ROI_GEOMETRY");}
}
