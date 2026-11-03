export default function HelloModule({React,request}) {
 const [rows,setRows]=React.useState(null),[error,setError]=React.useState('');
 React.useEffect(()=>{let active=true;request('/api/tenant/v1/modules/hello-module/records').then(data=>{if(active)setRows(data)}).catch(e=>{if(active)setError(e.message)});return()=>{active=false}},[request]);
 return React.createElement('section',{className:'care-card'},
  React.createElement('p',null,'模块安装验证 · v1.0.0'),
  React.createElement('h1',null,'你好，这是独立安装的模块'),
  React.createElement('p',null,'这个页面来自模块包，没有写入企业前端的固定页面列表。'),
  error?React.createElement('p',{role:'alert'},error):rows?rows.map((r,i)=>React.createElement('p',{key:i},r.message)):React.createElement('p',{role:'status'},'正在验证后端接口…')
 );
}
