export class Selection {
  constructor() { this.clear(); }
  clear() { this.all = false; this.ranges = []; this.anchor = -1; this.focus = -1; }
  contains(index) { const inside = this.ranges.some(([a,b]) => a <= index && index < b); return this.all ? !inside : inside; }
  count(total) { const n = this.ranges.reduce((sum,[a,b]) => sum + Math.max(0,Math.min(total,b)-a),0); return this.all ? total-n : n; }
  add(a,b) {
    const output=[];
    for (const [x,y] of [...this.ranges,[a,b]].sort((x,y)=>x[0]-y[0])) {
      const last=output.at(-1); if(last && x<=last[1]) last[1]=Math.max(last[1],y); else output.push([x,y]);
    }
    this.ranges=output;
  }
  remove(a,b) { this.ranges=this.ranges.flatMap(([x,y])=> y<=a || x>=b ? [[x,y]] : [...(x<a?[[x,a]]:[]),...(y>b?[[b,y]]:[])]); }
  choose(index,{ctrl=false,shift=false}={}) {
    if(shift && this.anchor>=0) {
      const a=Math.min(this.anchor,index),b=Math.max(this.anchor,index)+1;
      if(!ctrl){this.all=false;this.ranges=[];} if(this.all)this.remove(a,b);else this.add(a,b);
    } else if(ctrl) {
      const selected=this.contains(index); if(selected!==this.all)this.remove(index,index+1);else this.add(index,index+1); this.anchor=index;
    } else {this.all=false;this.ranges=[[index,index+1]];this.anchor=index;}
    this.focus=index;
  }
  selectAll() {this.all=true;this.ranges=[];}
  payload() {return {all:this.all,ranges:this.ranges.map(r=>[...r])};}
}
