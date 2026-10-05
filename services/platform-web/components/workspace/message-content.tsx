"use client";

import { memo } from "react";
import Markdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";
import { CopyButton } from "./copy-button";

const components: Components = {
  pre({ children, node }) {
    const code = node?.children.find((child) => child.type === "element" && child.tagName === "code");
    const text = code?.type === "element" ? code.children.map((child) => child.type === "text" ? child.value : "").join("") : "";
    const classes = code?.type === "element" ? code.properties.className : [];
    const language = Array.isArray(classes) ? String(classes.find((name) => String(name).startsWith("language-")) ?? "").replace(/^language-/, "") : "";
    return (
      <div className="markdown-code">
        <div className="markdown-code-header"><span>{language || "代码"}</span><CopyButton text={text} kind="代码" /></div>
        <pre tabIndex={0} aria-label="代码（可横向滚动）">{children}</pre>
      </div>
    );
  },
  table({ children }) {
    return <div className="markdown-table-scroll" role="region" tabIndex={0} aria-label="表格（可横向滚动）"><table>{children}</table></div>;
  },
  a({ children, href }) {
    return href ? <a href={href} target="_blank" rel="noopener noreferrer">{children}</a> : <span>{children}</span>;
  },
  // Agent-provided images must not silently fetch remote tracking URLs.
  img({ src, alt }) {
    return src ? <a href={src} target="_blank" rel="noopener noreferrer">查看图片：{alt || "图片"}</a> : <span>{alt}</span>;
  }
};
const plugins = [remarkGfm];

export const MessageContent = memo(function MessageContent({ content }: { content: string }) {
  return <div className="message-content markdown-body"><Markdown remarkPlugins={plugins} components={components} skipHtml>{content}</Markdown></div>;
});
