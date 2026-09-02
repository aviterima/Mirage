// Generates stylized street-grid map SVGs with routes that snap to the grid.
import { writeFileSync } from 'node:fs';

const LAND='#e9edf2', WATER='#cfe0ef', PARK='#dbe9d4', ROAD='#ffffff', BLOCK='#dfe3ea';
const ACC='#4F46E5', ACC_DK='#312BA6';

// vertical avenues and horizontal streets (px)
const AV=[20,74,128,182,236,290,344,398];
const HS=(H)=>{const ys=[];for(let y=60;y<H+40;y+=80)ys.push(y);return ys;};

function roads(W,H,arterialsX=[182],arterialsY=[380],diagonal=true){
  const ys=HS(H); let s='';
  // minor grid
  s+=`<g stroke="${ROAD}" stroke-width="6" fill="none" stroke-linecap="round" opacity="0.95">`;
  for(const x of AV) s+=`<path d="M${x} -10 L ${x} ${H+10}"/>`;
  for(const y of ys) s+=`<path d="M-10 ${y} L ${W+10} ${y}"/>`;
  s+=`</g>`;
  // arterials (thick)
  s+=`<g stroke="${ROAD}" stroke-width="13" fill="none" stroke-linecap="round">`;
  for(const x of arterialsX) s+=`<path d="M${x} -10 L ${x} ${H+10}"/>`;
  for(const y of arterialsY) s+=`<path d="M-10 ${y} L ${W+10} ${y}"/>`;
  if(diagonal) s+=`<path d="M-10 ${H+10} L ${W+10} ${H*0.15}"/>`;
  s+=`</g>`;
  return s;
}

function blocks(cells){
  let s=`<g fill="${BLOCK}">`;
  for(const [x,y,w,h] of cells) s+=`<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="3"/>`;
  return s+`</g>`;
}

// route from list of [x,y] grid nodes
function polyPath(pts){return 'M'+pts.map(p=>p.join(' ')).join(' L ');}
function sub(pts,frac){ // portion of polyline up to frac (0..1) by length -> {path, end:[x,y]}
  let segs=[],total=0;
  for(let i=1;i<pts.length;i++){const dx=pts[i][0]-pts[i-1][0],dy=pts[i][1]-pts[i-1][1];const d=Math.hypot(dx,dy);segs.push(d);total+=d;}
  let target=total*frac, acc=0, out=[pts[0]];
  for(let i=1;i<pts.length;i++){
    if(acc+segs[i-1]>=target){const t=(target-acc)/segs[i-1];const x=pts[i-1][0]+(pts[i][0]-pts[i-1][0])*t;const y=pts[i-1][1]+(pts[i][1]-pts[i-1][1])*t;out.push([Math.round(x),Math.round(y)]);return {path:polyPath(out),end:[Math.round(x),Math.round(y)]};}
    acc+=segs[i-1];out.push(pts[i]);
  }
  return {path:polyPath(pts),end:pts[pts.length-1]};
}

function mapSvg(W,H,{water,park,cells,route,carFrac}={}){
  let s=`<svg viewBox="0 0 ${W} ${H}" width="${W}" height="${H}" style="position:absolute;top:0;left:0" preserveAspectRatio="xMidYMid slice">`;
  s+=`<rect width="${W}" height="${H}" fill="${LAND}"/>`;
  if(park) s+=`<path d="${park}" fill="${PARK}"/>`;
  if(water) s+=`<path d="${water}" fill="${WATER}"/>`;
  s+=roads(W,H);
  if(cells) s+=blocks(cells);
  if(route){
    const full=polyPath(route);
    s+=`<path d="${full}" fill="none" stroke="${ROAD}" stroke-width="10" stroke-linecap="round" stroke-linejoin="round"/>`;
    s+=`<path d="${full}" fill="none" stroke="${ACC}" stroke-width="6" stroke-linecap="round" stroke-linejoin="round"/>`;
    if(carFrac!=null){const tr=sub(route,carFrac);
      s+=`<path d="${tr.path}" fill="none" stroke="${ACC_DK}" stroke-width="6" stroke-linecap="round" stroke-linejoin="round"/>`;
      s+=`<path d="${tr.path}" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" stroke-dasharray="1 8"/>`;
    }
    // endpoints
    s+=`<circle cx="${route[0][0]}" cy="${route[0][1]}" r="7" fill="#16A34A" stroke="#fff" stroke-width="3"/>`;
    s+=`<circle cx="${route.at(-1)[0]}" cy="${route.at(-1)[1]}" r="7" fill="${ACC}" stroke="#fff" stroke-width="3"/>`;
  }
  s+=`</svg>`;
  return s;
}

// ---- RouteSetup map (390 x 344), route snapped to grid ----
const routeR=[[74,96],[74,232],[236,232],[236,300]];
const mapRoute=mapSvg(390,344,{
  water:'M330 -10 L 400 -10 L 400 200 Q 360 150 330 60 Z',
  park:'M-10 250 Q 60 235 120 258 L 150 320 Q 70 340 -10 320 Z',
  cells:[[128,160,54,52],[182,232,54,60],[20,140,40,72]],
  route:routeR
});

// ---- LiveHUD map (390 x 844), longer route + car partway ----
const routeL=[[128,150],[128,380],[290,380],[290,620],[344,620],[344,706]];
const carFrac=0.62;
const carPos=sub(routeL,carFrac).end;
const mapLive=mapSvg(390,844,{
  water:'M344 470 L 400 470 L 400 844 L 300 844 Q 330 650 344 560 Z',
  park:'M-10 120 Q 90 95 190 140 L 210 210 Q 100 235 -10 215 Z',
  cells:[[182,300,54,60],[236,460,54,70],[20,540,88,60]],
  route:routeL, carFrac
});

// ---- Main map (390 x 844), no route, center pin at an intersection ----
const mapMain=mapSvg(390,844,{
  water:'M344 470 L 400 470 L 400 844 L 300 844 Q 330 650 344 560 Z',
  park:'M-10 120 Q 90 95 190 140 L 210 210 Q 100 235 -10 215 Z',
  cells:[[74,300,54,60],[236,220,80,54],[20,540,88,60]]
});
const mainPin=[182,380];

writeFileSync('mapRoute.svg',mapRoute);
writeFileSync('mapLive.svg',mapLive);
writeFileSync('mapMain.svg',mapMain);
console.log('routeR end A',routeR[0],'B',routeR.at(-1));
console.log('routeL end A',routeL[0],'B',routeL.at(-1),'car',carPos);
console.log('mainPin',mainPin);
console.log('sizes',mapRoute.length,mapLive.length,mapMain.length);
