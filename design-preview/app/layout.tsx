import type { Metadata } from 'next';
import './globals.css';
export const metadata: Metadata = {title:'Internet Restrictor — Five design directions',description:'Explore five considered visual directions across the dashboard, browser gate, and settings.'};
export default function RootLayout({children}:Readonly<{children:React.ReactNode}>){return <html lang="en"><body>{children}</body></html>}
