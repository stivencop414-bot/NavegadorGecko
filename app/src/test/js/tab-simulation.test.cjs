const {test}=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,'../../main/assets/extensions/nexo_network/simulation.js'),'utf8');
async function harness(profile){
 let native=0;const geo={getCurrentPosition:callback=>{native++;callback({real:true})},watchPosition:()=>7,clearWatch(){}};
 class Context { getImageData(){return {data:new Uint8Array([10,20,30,255,40,50,60,255])}} }
 class Canvas {}
 Canvas.prototype.toDataURL=function(){return 'native'};
 class WebGL {getParameter(id){return id===999?42:'original'}}
 const page={navigator:{geolocation:geo},HTMLCanvasElement:Canvas,CanvasRenderingContext2D:Context,WebGLRenderingContext:WebGL};
 const context=vm.createContext({window:{wrappedJSObject:page},location:{hostname:'example.com'},exportFunction:fn=>fn,cloneInto:object=>object,
  browser:{runtime:{sendMessage:async()=>profile}}});vm.runInContext(source,context);await new Promise(setImmediate);
 return {geo,page,native:()=>native};
}
test('geolocation simulation returns requested coordinates without calling native location',async()=>{
 const h=await harness({geo:{lat:4.7,lon:-74.1},burner:false});let position;
 h.geo.getCurrentPosition(value=>position=value);await new Promise(setImmediate);
 assert.equal(position.coords.latitude,4.7);assert.equal(position.coords.longitude,-74.1);assert.equal(h.native(),0);
 let updates=0;const id=h.geo.watchPosition(()=>updates++);h.geo.clearWatch(id);await new Promise(setImmediate);assert.equal(updates,0);
});
test('normal pages keep native geolocation and do not modify canvas or WebGL',async()=>{
 const h=await harness({geo:null,burner:false});let position;h.geo.getCurrentPosition(value=>position=value);
 assert.equal(position.real,true);assert.equal(h.native(),1);assert.equal(new h.page.WebGLRenderingContext().getParameter(37445),'original');
 assert.equal(new h.page.CanvasRenderingContext2D().getImageData().data[0],10);
});
test('Burner canvas variation is stable for the context and WebGL preserves unrelated capabilities',async()=>{
 const h=await harness({geo:null,burner:true,seed:42});const context=new h.page.CanvasRenderingContext2D();
 const first=Array.from(context.getImageData().data),second=Array.from(context.getImageData().data);
 assert.deepEqual(first,second);assert.notDeepEqual(first,[10,20,30,255,40,50,60,255]);
 const gl=new h.page.WebGLRenderingContext();assert.notEqual(gl.getParameter(37445),'original');assert.equal(gl.getParameter(999),42);
});
