import type {Locator} from '@playwright/test';

/** Read the object URL the SVG actually consumes, never a second HTTP download or CDP body. */
export async function displayedResultBytes(layer:Locator){
 return layer.locator('image').evaluate(async node=>{
  const source=(node as SVGImageElement).href.baseVal;
  if(!node.isConnected||!source.startsWith('blob:'))throw Error('Displayed synthetic Blob unavailable');
  // A local Blob read, not a request to the server. Capture before any subsequent URL cleanup.
  const response=await fetch(source,{signal:AbortSignal.timeout(5000)});
  if(!response.ok)throw Error('Displayed Blob read failed');
  const blob=await response.blob();
  if(blob.type!=='image/png'||blob.size<33||blob.size>24576)throw Error('Displayed PNG bounds invalid');
  const bytes=new Uint8Array(await blob.arrayBuffer());
  const sha256=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),v=>v.toString(16).padStart(2,'0')).join('');
  const bitmap=await createImageBitmap(blob);
  try{
   if(bitmap.width!==64||bitmap.height!==64)throw Error('Displayed PNG dimensions invalid');
   const canvas=document.createElement('canvas');canvas.width=64;canvas.height=64;
   const context=canvas.getContext('2d');if(!context)throw Error('Pixel decoder unavailable');
   context.drawImage(bitmap,0,0);
   return {sha256,magic:Array.from(bytes.slice(0,8)),width:bitmap.width,height:bitmap.height,
    first:Array.from(context.getImageData(8,8,1,1).data),last:Array.from(context.getImageData(56,56,1,1).data)};
  }finally{bitmap.close();}
 });
}
