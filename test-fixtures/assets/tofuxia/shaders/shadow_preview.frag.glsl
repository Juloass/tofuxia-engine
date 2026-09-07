#version 450
layout(set=0,binding=1) uniform sampler2D shadowMap;
layout(location=0) in vec2 uv;
layout(location=0) out vec4 outColor;
void main(){ float d=texture(shadowMap,uv).r; outColor=vec4(vec3(d),1.0); }
