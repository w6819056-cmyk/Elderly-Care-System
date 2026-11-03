import { request } from './client'
import { publicRequest } from './storePortal'
export interface Photo{url:string;caption:string}
export interface SiteContent{title:string;subtitle:string;introduction:string;philosophy:string;publicAddress:string;publicPhone:string;visitNote:string;heroImage:string;gallery:Photo[]}
export interface SiteEditor{draft:SiteContent;submitted:SiteContent|null;published:SiteContent|null;status:string;reviewNote:string|null;reviewToken:string}
export interface PublicSite{name:string;storeNo:string|null;content:SiteContent|null}
export interface StoreCard{storeNo:string;name:string;publicAddress:string;heroImage:string}
export const siteApi={brand:()=>publicRequest<PublicSite>('/api/public/site/brand'),store:(no:string)=>publicRequest<PublicSite>(`/api/public/site/stores/${encodeURIComponent(no)}`),stores:()=>publicRequest<StoreCard[]>('/api/public/site/stores'),edit:(id:string)=>request<SiteEditor>(`/api/tenant/v1/site-content/${encodeURIComponent(id)}`),save:(id:string,c:SiteContent)=>request(`/api/tenant/v1/site-content/${encodeURIComponent(id)}`,{method:'PUT',body:JSON.stringify(c)}),action:(id:string,action:string,note='',reviewToken='')=>request(`/api/tenant/v1/site-content/${encodeURIComponent(id)}/${action}`,{method:'POST',body:JSON.stringify({note,reviewToken})})}
