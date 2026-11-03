const key='customer.access_token'
export interface CustomerProfile{publicId:string;username:string;displayName:string}
export interface CustomerBooking{bookingNo:string;storeNo:string;storeName:string;visitorName:string;phone:string;visitType:string;preferredDate:string;status:string;remark:string|null}
export const customerToken=()=>localStorage.getItem(key)
export function clearCustomer(){localStorage.removeItem(key)}
export async function customerRequest<T>(path:string,body?:unknown):Promise<T>{
 const token=customerToken();const base=import.meta.env.VITE_API_BASE_URL?.replace(/\/$/,'')??''
 const response=await fetch(base+'/api/public/customer'+path,{method:body?'POST':'GET',headers:{...(body?{'Content-Type':'application/json'}:{}),...(token?{'X-Customer-Token':token}:{})},body:body?JSON.stringify(body):undefined})
 const result=await response.json();if(!response.ok||result.code!=='OK'){if(response.status===401)clearCustomer();throw new Error(result.message||'请求失败')};return result.data
}
export async function customerSignIn(mode:'login'|'register',body:unknown){const result=await customerRequest<{accessToken:string;profile:CustomerProfile}>('/'+mode,body);localStorage.setItem(key,result.accessToken);return result.profile}
