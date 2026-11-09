"""Run the production GLES shaders on Mesa/EGL; compare against a NumPy DCT oracle."""
import ctypes as C
import re
from pathlib import Path
import numpy as np

E=C.CDLL('libEGL.so.1')
E.eglGetProcAddress.restype=C.c_void_p
E.eglGetProcAddress.argtypes=[C.c_char_p]
def egl(name,restype,args):
 f=getattr(E,name);f.restype=restype;f.argtypes=args;return f
getplatform=C.CFUNCTYPE(C.c_void_p,C.c_uint,C.c_void_p,C.POINTER(C.c_int))(E.eglGetProcAddress(b'eglGetPlatformDisplayEXT'))
d=getplatform(0x31DD,None,None)
a=C.c_int();b=C.c_int()
assert egl('eglInitialize',C.c_uint,[C.c_void_p,C.POINTER(C.c_int),C.POINTER(C.c_int)])(d,C.byref(a),C.byref(b))
assert egl('eglBindAPI',C.c_uint,[C.c_uint])(0x30A0)
attrs=(C.c_int*13)(0x3033,1,0x3040,4,0x3024,8,0x3023,8,0x3022,8,0x3038,0,0)
config=C.c_void_p();count=C.c_int()
assert egl('eglChooseConfig',C.c_uint,[C.c_void_p,C.POINTER(C.c_int),C.POINTER(C.c_void_p),C.c_int,C.POINTER(C.c_int)])(d,attrs,C.byref(config),1,C.byref(count)) and count.value
ctx=egl('eglCreateContext',C.c_void_p,[C.c_void_p,C.c_void_p,C.c_void_p,C.POINTER(C.c_int)])(d,config,None,(C.c_int*3)(0x3098,2,0x3038))
assert ctx
assert egl('eglMakeCurrent',C.c_uint,[C.c_void_p,C.c_void_p,C.c_void_p,C.c_void_p])(d,None,None,ctx)
def gl(name,ret,args):
 return C.CFUNCTYPE(ret,*args)(E.eglGetProcAddress(name.encode()))
u=C.c_uint;i=C.c_int;f=C.c_float;p=C.c_void_p
createShader=gl('glCreateShader',u,[u]);shaderSource=gl('glShaderSource',None,[u,i,C.POINTER(C.c_char_p),p]);compileShader=gl('glCompileShader',None,[u]);getShaderiv=gl('glGetShaderiv',None,[u,u,C.POINTER(i)]);getShaderInfo=gl('glGetShaderInfoLog',None,[u,i,p,p])
createProgram=gl('glCreateProgram',u,[]);attachShader=gl('glAttachShader',None,[u,u]);linkProgram=gl('glLinkProgram',None,[u]);getProgramiv=gl('glGetProgramiv',None,[u,u,C.POINTER(i)]);getProgramInfo=gl('glGetProgramInfoLog',None,[u,i,p,p]);useProgram=gl('glUseProgram',None,[u]);getUniform=gl('glGetUniformLocation',i,[u,C.c_char_p]);uniform1i=gl('glUniform1i',None,[i,i]);uniform1f=gl('glUniform1f',None,[i,f]);uniform2f=gl('glUniform2f',None,[i,f,f]);getAttrib=gl('glGetAttribLocation',i,[u,C.c_char_p]);enableAttrib=gl('glEnableVertexAttribArray',None,[u]);attribPointer=gl('glVertexAttribPointer',None,[u,i,u,u,i,p]);draw=gl('glDrawArrays',None,[u,i,i]);viewport=gl('glViewport',None,[i,i,i,i]);disable=gl('glDisable',None,[u]);error=gl('glGetError',u,[])
genTextures=gl('glGenTextures',None,[i,C.POINTER(u)]);bindTexture=gl('glBindTexture',None,[u,u]);activeTexture=gl('glActiveTexture',None,[u]);texParam=gl('glTexParameteri',None,[u,u,i]);texImage=gl('glTexImage2D',None,[u,i,i,i,i,i,u,u,p]);genFbos=gl('glGenFramebuffers',None,[i,C.POINTER(u)]);bindFbo=gl('glBindFramebuffer',None,[u,u]);attachTex=gl('glFramebufferTexture2D',None,[u,u,u,u,i]);statusFbo=gl('glCheckFramebufferStatus',u,[u]);readPixels=gl('glReadPixels',None,[i,i,i,i,u,u,p]);deleteTextures=gl('glDeleteTextures',None,[i,C.POINTER(u)]);deleteFbos=gl('glDeleteFramebuffers',None,[i,C.POINTER(u)])

def compile(source,kind):
 shader=createShader(kind);text=C.c_char_p(source.encode());shaderSource(shader,1,C.byref(text),None);compileShader(shader)
 ok=i();getShaderiv(shader,0x8B81,C.byref(ok))
 if not ok.value:
  buf=C.create_string_buffer(32768);getShaderInfo(shader,len(buf),None,buf);raise AssertionError(buf.value.decode())
 return shader
source=(Path(__file__).resolve().parents[1] / 'app/src/main/java/com/galaxysensorlab/JpegCompression.kt').read_text()
vertex=re.search(r'val VERTEX = """(.*?)"""',source,re.S)[1]
fragment=re.search(r'val FRAGMENT = """(.*?)"""',source,re.S)[1]
programs=[]
for stage in range(4):
 pr=createProgram();attachShader(pr,compile(vertex,0x8B31));attachShader(pr,compile(f'#define STAGE {stage}\n'+fragment,0x8B30));linkProgram(pr)
 ok=i();getProgramiv(pr,0x8B82,C.byref(ok))
 if not ok.value:
  buf=C.create_string_buffer(32768);getProgramInfo(pr,len(buf),None,buf);raise AssertionError(buf.value.decode())
 programs.append(pr)
print('PASS: all four production GLSL programs compile and link')

def table(name):
 return np.array([int(v) for v in re.search(r'val '+name+r' = intArrayOf\((.*?)\)',source,re.S)[1].replace('\n','').split(',')]).reshape(8,8)
tables=np.stack([table('LUMA'),table('CHROMA'),table('CHROMA')],-1)
quant=np.concatenate([tables.astype(np.uint8),np.full((8,8,1),255,np.uint8)],axis=-1)
quad=np.array([[-1,-1,0,1],[1,-1,0,1],[-1,1,0,1],[1,1,0,1]],np.float32)
disable(0x0BD0);disable(0x0BE2)

def texture(w,h,data=None):
 t=u();genTextures(1,C.byref(t));bindTexture(0x0DE1,t)
 for param,value in [(0x2801,0x2600),(0x2800,0x2600),(0x2802,0x812F),(0x2803,0x812F)]: texParam(0x0DE1,param,value)
 texImage(0x0DE1,0,0x1908,w,h,0,0x1908,0x1401,None if data is None else data.ctypes.data_as(p))
 return t

def target(t):
 fb=u();genFbos(1,C.byref(fb));bindFbo(0x8D40,fb);attachTex(0x8D40,0x8CE0,0x0DE1,t,0);assert statusFbo(0x8D40)==0x8CD5;return fb

def gpu(image,amount,horizontal=True,split=-1):
 h,w=image.shape[:2];ph=(h+7)//8*8;pw=(w+7)//8*8;packedW=pw*(2 if horizontal else 1);packedH=ph*(1 if horizontal else 2)
 inp=texture(w,h,image);a=texture(packedW,packedH);b=texture(packedW,packedH);out=texture(w,h);qt=texture(8,8,quant);fa=target(a);fb=target(b);fo=target(out)
 quality=100-95*amount;scale=5000/quality if quality<50 else 200-2*quality
 for stage,pr in enumerate(programs):
  useProgram(pr);bindFbo(0x8D40,[fa,fb,fa,fo][stage]);viewport(0,0,*((w,h) if stage==3 else (packedW,packedH)))
  def loc(name,required=True):
   v=getUniform(pr,name.encode())
   if required: assert v>=0,(stage,name)
   return v
  for unit,name,tex in [(0,'uTex',[inp,a,b,a][stage]),(1,'uQuant',qt)] if stage==1 else [(0,'uTex',[inp,a,b,a][stage])]:
   activeTexture(0x84C0+unit);bindTexture(0x0DE1,tex);uniform1i(loc(name),unit)
  uniform2f(loc('uPadded'),pw,ph);uniform1f(loc('uHorizontalBanks'),float(horizontal))
  if stage in [0,3]:uniform2f(loc('uSize'),w,h)
  if stage==1:uniform1f(loc('uScale'),scale)
  if stage==3:
   for unit,name in [(1,'uOriginal'),(2,'uDeveloped')]:
    activeTexture(0x84C0+unit);bindTexture(0x0DE1,inp);uniform1i(loc(name),unit)
   uniform1f(loc('uMix'),min(1,amount*10));uniform1f(loc('uSplit'),split)
  attr=getAttrib(pr,b'aFramePosition');enableAttrib(attr);attribPointer(attr,4,0x1406,0,0,quad.ctypes.data_as(p));draw(5,0,4)
  assert error()==0,stage
 result=np.empty_like(image);readPixels(0,0,w,h,0x1908,0x1401,result.ctypes.data_as(p));assert error()==0
 for t in [inp,a,b,out,qt]:deleteTextures(1,C.byref(t))
 for fb in [fa,fb,fo]:deleteFbos(1,C.byref(fb))
 return result

# Independent mathematical reference: block DCT with matrix multiplication.
D=np.cos((np.arange(8)[None,:]*2+1)*np.arange(8)[:,None]*np.pi/16)*.5;D[0,:]=np.sqrt(1/8)
def reference(image,amount):
 h,w=image.shape[:2];rgb=(image[:,:,:3]/255.)**(1/2.2)
 signal=rgb@np.array([[.299,-.168736,.5],[.587,-.331264,-.418688],[.114,.5,-.081312]])
 signal[:,:,0]-=.5;signal=np.pad(signal,((0,(-h)%8),(0,(-w)%8),(0,0)),mode='edge')
 q=100-95*amount;scale=5000/q if q<50 else 200-2*q;steps=np.clip(np.floor((tables*scale+50)/100),1,255)/255
 result=np.zeros_like(signal)
 for y in range(0,len(signal),8):
  for x in range(0,signal.shape[1],8):
   for ch in range(3):
    coeff=D@signal[y:y+8,x:x+8,ch]@D.T
    coeff=np.sign(coeff)*np.floor(abs(coeff)/steps[:,:,ch]+.5)*steps[:,:,ch]
    result[y:y+8,x:x+8,ch]=D.T@coeff@D
 c=result[:h,:w];Y=c[:,:,0]+.5
 rgb=np.stack([Y+1.402*c[:,:,2],Y-.344136*c[:,:,1]-.714136*c[:,:,2],Y+1.772*c[:,:,1]],-1)
 rgb=np.clip(rgb,0,1)**2.2;rgb=image[:,:,:3]/255.*(1-min(1,amount*10))+rgb*min(1,amount*10)
 return np.floor(rgb*255+.5).astype(np.uint8)

for w,h in [(32,24),(30,22),(18,10),(2,2)]:
 yy,xx=np.mgrid[:h,:w];rng=np.random.default_rng(7)
 image=np.empty((h,w,4),np.uint8);image[:,:,:3]=rng.integers(25,230,(h,w,3));image[:,:,3]=255
 for amount in [.05,.5,1]:
  result=gpu(image,amount);expected=reference(image,amount);delta=abs(result[:,:,:3].astype(float)-expected)
  assert delta.mean()<1.5,(w,h,amount,delta.mean(),delta.max())
  alternate=gpu(image,amount,False)
  assert abs(result.astype(float)-alternate).max()<=1
  print(f'PASS: {w}x{h}, amount={amount}, CPU reference mean error={delta.mean():.3f}/255, both bank orientations')
 split=gpu(image,1,split=.47);left=xx/w<.47-2/w
 assert np.array_equal(split[left],image[left])
 print(f'PASS: {w}x{h}, original half preserved exactly')
# Check visible progression on fine detail, and spatial content dependence.
w,h=320,240;yy,xx=np.mgrid[:h,:w];detail=np.where((xx//2+yy//3)%2==0,55,-35)
image=np.empty((h,w,4),np.uint8);image[:,:,:3]=np.stack([100+xx*70//w+detail,110+yy*50//h-detail,140+detail],-1);image[:,:,3]=255
mild=gpu(image,.05);strong=gpu(image,1)
err=lambda a: abs(a[:,:,:3].astype(float)-image[:,:,:3]).mean()
assert err(strong)>err(mild)>0
print(f'PASS: intensity progression: mild MAE {err(mild):.2f}, strong {err(strong):.2f}')
print('All production shader checks passed.')
