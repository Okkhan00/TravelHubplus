'use strict';

/** Returns true if a visible password field exists. */
const DETECT = `(function(){try{var l=document.querySelectorAll('input[type=password]');for(var i=0;i<l.length;i++){var r=l[i].getBoundingClientRect();if(r.width>0&&r.height>0&&!l[i].disabled){return true;}}return false;}catch(e){return false;}})()`;

const FILL_BODY = `
(function(u,p,submit){
 try{
  function vis(e){var r=e.getBoundingClientRect();var s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none'&&!e.disabled&&!e.readOnly;}
  var pw=Array.prototype.slice.call(document.querySelectorAll('input[type=password]')).filter(vis)[0];
  if(!pw){return 'nofield';}
  var scope=pw.form||document;
  var cands=Array.prototype.slice.call(scope.querySelectorAll('input')).filter(function(e){var t=(e.type||'text').toLowerCase();return vis(e)&&['text','email','tel','number'].indexOf(t)>=0;});
  var user=null;
  for(var i=0;i<cands.length;i++){if(cands[i].compareDocumentPosition(pw)&Node.DOCUMENT_POSITION_FOLLOWING){user=cands[i];}}
  function setv(e,v){var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');d.set.call(e,v);e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}
  if(user&&u){setv(user,u);}
  setv(pw,p);
  if(submit){
   var f=pw.form;var b=f&&f.querySelector('button[type=submit],input[type=submit]');
   if(b){b.click();return 'submitted';}
   if(f&&f.requestSubmit){f.requestSubmit();return 'submitted';}
   return 'filled';
  }
  return 'filled';
 }catch(e){return 'error';}
})`;

/** JSON string literal that is also safe as a JS literal (escapes quotes, <, >, &, control chars, U+2028/9). */
function quote(s) {
  let out = '"';
  for (const ch of String(s)) {
    const c = ch.codePointAt(0);
    if (ch === '"') out += '\\"';
    else if (ch === '\\') out += '\\\\';
    else if (ch === '\n') out += '\\n';
    else if (ch === '\r') out += '\\r';
    else if (ch === '\t') out += '\\t';
    else if (ch === '<' || ch === '>' || ch === '&' || c < 0x20 || c === 0x2028 || c === 0x2029) out += '\\u' + c.toString(16).padStart(4, '0');
    else out += ch;
  }
  return out + '"';
}

function fill(username, password, submit) {
  return FILL_BODY + '(' + quote(username) + ',' + quote(password) + ',' + (submit ? 'true' : 'false') + ')';
}

module.exports = { DETECT, fill, quote };
