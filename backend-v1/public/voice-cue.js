// Unlock audio in the Start button gesture; sound only after the call is ready.
export function createVoiceCue(AudioContextClass=globalThis.AudioContext||globalThis.webkitAudioContext){
 let context;
 return {
  prepare(){
   try{context??=AudioContextClass?new AudioContextClass():null;context?.resume().catch(()=>{});}catch{}
  },
  play(){
   if(context?.state!=='running')return;
   try{
    const start=context.currentTime;
    for(const [offset,frequency] of [[0,660],[.11,880]]){
     const tone=context.createOscillator(),volume=context.createGain();
     tone.type='sine';tone.frequency.value=frequency;
     volume.gain.setValueAtTime(0,start+offset);
     volume.gain.linearRampToValueAtTime(.07,start+offset+.015);
     volume.gain.exponentialRampToValueAtTime(.001,start+offset+.14);
     tone.connect(volume);volume.connect(context.destination);
     tone.onended=()=>{tone.disconnect();volume.disconnect();};
     tone.start(start+offset);tone.stop(start+offset+.15);
    }
   }catch{} // Audio feedback must never prevent a call from working.
  },
  dispose(){const previous=context;context=null;try{previous?.close().catch(()=>{});}catch{}}
 };
}
