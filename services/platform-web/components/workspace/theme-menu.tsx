"use client";

import { Monitor, Moon, Sun } from "lucide-react";
import { useTheme } from "next-themes";
import { Button } from "@/components/ui/button";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";

export function ThemeMenu() {
  const { setTheme } = useTheme();
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon" aria-label="切换主题">
          <Sun className="dark:hidden" /><Moon className="hidden dark:block" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuItem onClick={() => setTheme("light")}><Sun />浅色</DropdownMenuItem>
        <DropdownMenuItem onClick={() => setTheme("dark")}><Moon />深色</DropdownMenuItem>
        <DropdownMenuItem onClick={() => setTheme("system")}><Monitor />跟随系统</DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
